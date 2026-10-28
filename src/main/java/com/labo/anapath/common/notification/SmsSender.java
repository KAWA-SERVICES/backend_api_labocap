package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Porte unique par laquelle sortent les SMS du laboratoire.
 *
 * <p>Deux fonctionnalités en émettent — l'avis de facture téléchargeable et
 * l'avis de résultat disponible — et toutes deux ont les mêmes obligations :
 * respecter les bornes du contrat de l'éditeur, porter une clé d'idempotence,
 * et rester traçables. Les réunir ici évite que chacune les réinvente.</p>
 *
 * <p><b>Transport.</b> Les SMS partent par FluidPay ({@code POST /api/v1/sms}),
 * qui achemine via l'opérateur désigné par
 * {@link FluidPaySmsProperties#getProvider()} — OurVoice pour le laboratoire.
 * L'appel vocal, lui, ne passe pas par FluidPay, dont l'API ne fait que du SMS :
 * il part par FluidChat, voir {@link FluidChatClient}.</p>
 *
 * <p><b>Le repli, et sa raison.</b> Tant que la clé FluidPay n'est pas
 * renseignée, les SMS repartent par l'API OurVoice directe, comme avant. Sans
 * ce repli, mettre en service cette bascule sans la clé couperait du jour au
 * lendemain des avis qui fonctionnaient. Le repli ne joue que sur l'absence de
 * configuration : un refus de FluidPay — numéro invalide, quota épuisé — remonte
 * tel quel, car le renvoyer par un autre chemin ne ferait que le répéter.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsSender {

    /** Étiquette d'origine des SMS annonçant une facture téléchargeable. */
    public static final String SOURCE_FACTURE = "labocap_invoice";

    /** Étiquette d'origine des SMS annonçant un résultat disponible au retrait. */
    public static final String SOURCE_RESULTAT = "labocap_report";

    /** Longueur minimale d'une référence d'envoi, imposée par le contrat FluidPay. */
    private static final int REFERENCE_LONGUEUR_MIN = 15;

    /** Longueur maximale d'une référence d'envoi, imposée par le contrat FluidPay. */
    private static final int REFERENCE_LONGUEUR_MAX = 50;

    /** Clé {@code setting_apps} du jeton OurVoice, utilisé par le repli. */
    private static final String CLE_TOKEN_OURVOICE = "key_ourvoice";

    /** Clé {@code setting_apps} de l'adresse SMS d'OurVoice, utilisée par le repli. */
    private static final String CLE_ENDPOINT_OURVOICE = "link_ourvoice_sms";

    private final FluidPaySmsProperties properties;
    private final FluidPaySmsClient fluidPaySmsClient;
    private final OurVoiceClient ourVoiceClient;
    private final SettingAppRepository settingAppRepository;

    /**
     * Envoie un SMS.
     *
     * @param numero     destinataire, déjà mis au format international par
     *                   {@link PhoneNumbers#toInternational}
     * @param message    texte à envoyer
     * @param sourceType origine de l'envoi — {@link #SOURCE_FACTURE} ou
     *                   {@link #SOURCE_RESULTAT} — qui permet de retrouver les
     *                   envois d'une fonctionnalité dans le tableau de bord
     * @param sourceId   clé d'idempotence. <b>Le choix engage le comportement :</b>
     *                   un identifiant stable (celui de la facture) fait écarter
     *                   par FluidPay tout second envoi pour la même pièce ; un
     *                   identifiant tiré au sort autorise la relance, ce qu'attend
     *                   le bouton de renvoi manuel d'un avis de résultat.
     * @throws ExternalApiException si aucun transport n'est configuré, ou si
     *                              l'envoi est refusé
     */
    public void envoyer(String numero, String message, String sourceType, UUID sourceId) {
        if (numero == null || numero.isBlank()) {
            throw new ExternalApiException("SMS non envoyé : aucun numéro de destinataire.");
        }
        String texte = tronquer(message);

        if (properties.isUsable()) {
            FluidPaySmsResult resultat = fluidPaySmsClient.envoyer(new FluidPaySmsMessage(
                    properties.getProvider(),
                    numero,
                    texte,
                    referenceId(sourceType, sourceId),
                    sourceId.toString(),
                    sourceType,
                    sender()));
            log.info("SMS {} envoyé au {} via FluidPay/{} — batch={}, doublon={}",
                    sourceType, numero, properties.getProvider(),
                    resultat.batchId(), resultat.duplicate());
            return;
        }

        envoyerParOurVoice(numero, texte, sourceType);
    }

    /**
     * Repli historique : appel direct à l'API OurVoice, telle qu'elle était
     * appelée avant l'arrivée de FluidPay.
     *
     * <p>Les identifiants vivent dans {@code setting_apps} et non dans la
     * configuration du serveur — c'est l'écran Paramètres qui les porte depuis le
     * Laravel d'origine, et les déplacer obligerait à un déploiement pour changer
     * un jeton.</p>
     */
    private void envoyerParOurVoice(String numero, String texte, String sourceType) {
        Optional<String> jeton = valeurDeReglage(CLE_TOKEN_OURVOICE);
        Optional<String> endpoint = valeurDeReglage(CLE_ENDPOINT_OURVOICE);
        if (jeton.isEmpty() || endpoint.isEmpty()) {
            throw new ExternalApiException("Aucune passerelle SMS configurée : renseignez "
                    + "app.fluidpay.sms.api-key, ou les réglages " + CLE_TOKEN_OURVOICE
                    + " et " + CLE_ENDPOINT_OURVOICE + ".");
        }
        log.info("FluidPay non configuré : SMS {} envoyé au {} par OurVoice en direct",
                sourceType, numero);
        ourVoiceClient.sms(endpoint.get(), jeton.get(), numero, texte);
    }

    /**
     * Référence lisible de l'envoi, entre 15 et 50 caractères.
     *
     * <p>Elle reprend l'origine et la clé d'idempotence : lue dans le tableau de
     * bord de l'éditeur, elle dit de quelle pièce vient le SMS, sans avoir à
     * remonter dans les journaux du serveur.</p>
     */
    private String referenceId(String sourceType, UUID sourceId) {
        String identifiant = sourceId.toString().replace("-", "");
        int placeRestante = REFERENCE_LONGUEUR_MAX - identifiant.length() - 1;
        String prefixe = sourceType.length() > placeRestante
                ? sourceType.substring(0, placeRestante)
                : sourceType;
        String reference = prefixe + "-" + identifiant;
        // Le minimum est structurellement atteint — 32 caractères rien que pour
        // l'identifiant — mais la borne est celle du contrat, pas la nôtre.
        return reference.length() < REFERENCE_LONGUEUR_MIN
                ? (reference + "0".repeat(REFERENCE_LONGUEUR_MIN - reference.length()))
                : reference;
    }

    /**
     * Texte ramené dans la limite de l'éditeur.
     *
     * <p>Un message trop long fait rejeter l'envoi entier : mieux vaut un SMS
     * tronqué, qui prévient tout de même le destinataire, qu'aucun SMS. Le cas
     * est signalé, car il vient forcément d'un texte saisi dans Paramètres.</p>
     */
    private String tronquer(String message) {
        if (message == null) {
            throw new ExternalApiException("SMS non envoyé : message vide.");
        }
        if (message.length() <= FluidPaySmsProperties.LONGUEUR_MAX_MESSAGE) {
            return message;
        }
        log.warn("Message SMS de {} caractères tronqué à {} : vérifiez le texte "
                        + "saisi dans Paramètres → Communication Mobile",
                message.length(), FluidPaySmsProperties.LONGUEUR_MAX_MESSAGE);
        return message.substring(0, FluidPaySmsProperties.LONGUEUR_MAX_MESSAGE);
    }

    /**
     * Expéditeur affiché, ou {@code null} pour laisser FluidPay appliquer celui
     * du compte. Tronqué plutôt que rejeté, pour la même raison que le message.
     */
    private String sender() {
        String sender = properties.getSender();
        if (sender == null || sender.isBlank()) {
            return null;
        }
        if (sender.length() > FluidPaySmsProperties.LONGUEUR_MAX_SENDER) {
            log.warn("Expéditeur SMS « {} » tronqué à {} caractères",
                    sender, FluidPaySmsProperties.LONGUEUR_MAX_SENDER);
            return sender.substring(0, FluidPaySmsProperties.LONGUEUR_MAX_SENDER);
        }
        return sender;
    }

    /**
     * Valeur non vide d'un réglage, quelle que soit la branche qui le porte.
     *
     * <p>La lecture ne passe pas par {@code findByKey} : cette méthode lève dès
     * que deux branches définissent la même clé, ce qui est le cas normal d'un
     * réglage éditable par branche.</p>
     */
    private Optional<String> valeurDeReglage(String cle) {
        return settingAppRepository.findByKeyInOrderByCreatedAtAsc(java.util.List.of(cle)).stream()
                .map(SettingApp::getValue)
                .filter(valeur -> valeur != null && !valeur.isBlank())
                .findFirst();
    }
}

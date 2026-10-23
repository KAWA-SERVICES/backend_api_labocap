package com.labo.anapath.common.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Propriétés de la passerelle SMS FluidPay, lues depuis {@code application.yml}
 * sous le préfixe {@code app.fluidpay.sms}.
 *
 * <p>FluidPay agrège plusieurs opérateurs derrière un seul contrat
 * ({@code POST /api/v1/sms}) et se charge lui-même de la file d'envoi, de la
 * déduplication et des accusés de réception. Le laboratoire y reste client
 * d'OurVoice — désigné par {@link #provider} — mais ne lui parle plus
 * directement pour le SMS.</p>
 *
 * <p><b>Charge utile chiffrée.</b> Le serveur refuse une requête SMS en clair
 * ({@code missing_encrypted_data}) et réclame le même {@code encrypted_data} que
 * ses endpoints de paiement — ce que {@code docs/openapi.yaml} ne dit pas. D'où
 * {@link #encryptionKey}, à côté de {@link #apiKey}.</p>
 *
 * <p><b>L'appel vocal ne passe pas par ici.</b> FluidPay n'expose que du SMS ;
 * l'avis vocal de disponibilité d'un résultat continue de partir vers l'API
 * OurVoice directe — voir {@code OurVoiceClient#call}.</p>
 *
 * <pre>{@code
 * app:
 *   fluidpay:
 *     sms:
 *       enabled: true
 *       base-url: https://app.fluidpay.link
 *       api-key: <jeton Bearer du tableau de bord FluidPay>
 *       encryption-key: <clé de chiffrement, 32 caractères>
 *       provider: ourvoice
 *       sender: CAAP
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "app.fluidpay.sms")
@Getter
@Setter
public class FluidPaySmsProperties {

    /** Longueur maximale d'un identifiant d'expéditeur, imposée par les opérateurs. */
    public static final int LONGUEUR_MAX_SENDER = 11;

    /** Longueur maximale du texte d'un SMS acceptée par FluidPay. */
    public static final int LONGUEUR_MAX_MESSAGE = 1600;

    /**
     * Active l'envoi par FluidPay. Vrai par défaut, mais sans effet tant que la
     * clé manque : {@link #isUsable()} tranche, et l'envoi se replie alors sur
     * OurVoice — voir {@link SmsSender}.
     */
    private boolean enabled = true;

    /** Racine de l'API, sans barre oblique finale. */
    private String baseUrl = "https://app.fluidpay.link";

    /** Jeton Bearer du compte FluidPay. Jamais en dur : il autorise des envois facturés. */
    private String apiKey;

    /**
     * Clé de chiffrement du compte, 32 caractères.
     *
     * <p>Obligatoire : le serveur refuse une requête SMS non chiffrée
     * ({@code missing_encrypted_data}), quoi qu'en dise {@code docs/openapi.yaml}.
     * Distincte de {@link #apiKey}, qui authentifie ; celle-ci chiffre — voir
     * {@link FluidPayPayloadCipher}.</p>
     */
    private String encryptionKey;

    /**
     * Opérateur qui achemine le SMS, parmi ceux que FluidPay prend en charge
     * ({@code lafricamobile}, {@code ourvoice}). Le laboratoire achemine par
     * lafricamobile ; changer d'opérateur reste un réglage, pas un déploiement.
     *
     * <p>À ne pas confondre avec OurVoice, qui garde l'appel vocal : c'est
     * l'acheminement du SMS qui change ici, pas la voix.</p>
     */
    private String provider = "lafricamobile";

    /**
     * Nom affiché comme expéditeur sur le téléphone du destinataire.
     *
     * <p>Vide par défaut, et c'est délibéré : FluidPay retombe alors sur
     * l'expéditeur déclaré au niveau du compte de l'opérateur, seul à être
     * enregistré auprès de lui. Un nom non déclaré fait rejeter l'envoi.</p>
     */
    private String sender;

    /**
     * En-tête {@code User-Agent} des appels.
     *
     * <p>Le domaine est derrière Cloudflare, qui sert un challenge HTML — et non
     * du JSON — aux clients sans {@code User-Agent} explicite.</p>
     */
    private String userAgent = "LabocapAPI/1.0";

    /**
     * Vrai si la passerelle est utilisable : activée, avec une adresse, une clé
     * d'authentification et une clé de chiffrement.
     *
     * <p>La clé de chiffrement compte autant que l'autre : sans elle, chaque
     * envoi serait refusé. Mieux vaut se replier sur OurVoice — voir
     * {@link SmsSender} — que perdre les SMS un par un.</p>
     */
    public boolean isUsable() {
        return enabled && StringUtils.hasText(apiKey)
                && StringUtils.hasText(encryptionKey) && StringUtils.hasText(baseUrl);
    }

    /** URL complète de l'envoi de SMS, barre oblique finale de {@link #baseUrl} tolérée. */
    public String urlEnvoi() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/api/v1/sms";
    }
}

package com.labo.anapath.common.notification;

import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Textes des SMS envoyés aux patients, modifiables depuis l'écran Paramètres.
 *
 * <p>Ces messages portent le nom du laboratoire, ses horaires et son adresse :
 * autant de choses qui changent sans qu'un déploiement soit justifié. Ils vivent
 * donc dans {@code setting_apps}, sous l'onglet « Communication Mobile ».</p>
 *
 * <p><b>Le défaut reste dans le code, et c'est délibéré.</b> Une clé absente,
 * vide ou effacée par mégarde ne doit pas faire partir un SMS vide : le message
 * livré prend alors le relais. C'est aussi ce qui évite d'avoir à semer ces
 * valeurs par migration dans chaque branche.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsTemplates {

    /** Clé {@code setting_apps} du SMS annonçant un résultat disponible au retrait. */
    public static final String CLE_SMS_RESULTAT = "sms_resultat_body";

    /**
     * Clé {@code setting_apps} du SMS annonçant des résultats de biologie.
     *
     * <p>Distincte de {@link #CLE_SMS_RESULTAT} : le texte d'anatomie pathologique
     * nomme le cabinet d'anatomie pathologique, et le reprendre pour un bilan
     * sanguin induirait le patient en erreur. Pas de repli sur la clé
     * d'anatomie pathologique pour la même raison.</p>
     */
    public static final String CLE_SMS_RESULTAT_BIOLOGIE = "sms_resultat_biologie_body";

    /** Clé {@code setting_apps} du SMS annonçant une facture téléchargeable. */
    public static final String CLE_SMS_FACTURE = "sms_facture_body";

    /** Texte livré du SMS de résultat, repris tel quel du Laravel d'origine. */
    public static final String DEFAUT_SMS_RESULTAT =
            "Bonjour c'est le cabinet medical Anathomie pathologique adechinan situé à fifadji "
            + "vos résultats d'analyse sont maintenant disponible vous pouvez venir les recupérer "
            + "à tout moment pendant nos heures d'ouvertures. "
            + "Nous sommes ouvert du Lundi au vendredi de 08h à 17h Merci de votre confiance";

    /**
     * Texte livré du SMS de résultat de biologie : neutre (sans nom de
     * laboratoire, que chaque déploiement renseigne sous la clé ci-dessus) et
     * sous les 160 caractères.
     */
    public static final String DEFAUT_SMS_RESULTAT_BIOLOGIE =
            "Bonjour, vos résultats d'analyses sont disponibles. Vous pouvez venir les récupérer "
            + "pendant nos heures d'ouverture. Merci de votre confiance.";

    /**
     * Texte livré du SMS de facture, envoyé à la normalisation. Sans accent et
     * sous {@link #SMS_UNITE} caractères : une seule unité facturée, et un lien
     * que l'affichage ne coupe pas.
     */
    public static final String DEFAUT_SMS_FACTURE =
            "Dossier {demande}: resultat dans 3 semaines. Facture: {lien}";

    /** Variante pour une facture sans demande d'examen (consultation, prestation). */
    public static final String DEFAUT_SMS_FACTURE_SANS_DEMANDE = "Facture {code}: {lien}";

    /** Caractères d'un SMS d'une seule unité, sans accent (alphabet GSM). */
    static final int SMS_UNITE = 160;

    private final SettingAppRepository settingAppRepository;

    /**
     * Le texte du SMS de résultat, tel que paramétré ou, à défaut, tel que livré.
     *
     * @param branchId branche du compte-rendu ; {@code null} accepté
     */
    public String smsResultat(UUID branchId) {
        return modele(CLE_SMS_RESULTAT, branchId, DEFAUT_SMS_RESULTAT);
    }

    /**
     * Le texte du SMS de résultat de biologie, tel que paramétré ou, à défaut,
     * tel que livré.
     *
     * @param branchId branche du compte-rendu ; {@code null} accepté
     */
    public String smsResultatBiologie(UUID branchId) {
        return modele(CLE_SMS_RESULTAT_BIOLOGIE, branchId, DEFAUT_SMS_RESULTAT_BIOLOGIE);
    }

    /**
     * Le texte du SMS de facture : jetons {@code {code}}, {@code {demande}} et
     * {@code {lien}} remplacés, accents retirés.
     *
     * <p>Sans accent parce qu'un seul caractère accentué fait passer le SMS en
     * Unicode, où l'unité tombe à 70 caractères : le même texte coûterait deux
     * SMS. Un texte paramétré qui dépasse malgré tout une unité est écarté au
     * profit du texte livré — le lien, en fin de message, serait le premier
     * coupé. Une facture sans demande d'examen reçoit sa variante courte.</p>
     *
     * @param branchId branche de la facture ; {@code null} accepté
     * @param code     code de la facture ; chaîne vide acceptée
     * @param demande  code de la demande d'examen ; nul ou vide sans demande
     * @param lien     URL publique de téléchargement
     */
    public String smsFacture(UUID branchId, String code, String demande, String lien) {
        Map<String, String> jetons = Map.of("{code}", code == null ? "" : code,
                "{demande}", demande == null ? "" : demande, "{lien}", lien);
        if (demande == null || demande.isBlank()) {
            return sansAccent(remplacer(DEFAUT_SMS_FACTURE_SANS_DEMANDE, jetons));
        }
        String texte = sansAccent(remplacer(modele(CLE_SMS_FACTURE, branchId, DEFAUT_SMS_FACTURE), jetons));
        if (texte.length() > SMS_UNITE) {
            log.warn("SMS facture paramétré trop long ({} caractères) : texte livré utilisé", texte.length());
            texte = sansAccent(remplacer(DEFAUT_SMS_FACTURE, jetons));
        }
        return texte;
    }

    /** Lettres accentuées ramenées à leur base, tirets et guillemets typographiques à l'ASCII. */
    static String sansAccent(String texte) {
        return java.text.Normalizer.normalize(texte, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('\u2014', '-').replace('\u2013', '-')
                .replace('\u2019', '\'').replace('\u00A0', ' ');
    }

    /**
     * Valeur paramétrée d'une clé, ou le défaut si elle est absente ou vide.
     *
     * <p><b>Résolution en trois temps :</b> le texte de la branche concernée, puis
     * celui de la branche la plus ancienne — la branche mère, dont le libellé fait
     * référence tant qu'une antenne n'a pas écrit le sien — puis le texte livré.
     * Le repli sur la branche mère évite qu'ouvrir une antenne ne fasse repartir
     * ses SMS sur le texte d'usine.</p>
     *
     * <p>Le cas « vide » compte autant que le cas « absente » : l'écran Paramètres
     * enregistre une chaîne vide dès qu'un champ est effacé, et un SMS sans texte
     * serait envoyé — et facturé — pour rien.</p>
     *
     * <p>La lecture ne passe pas par {@code findByKey} : cette méthode lève dès que
     * deux branches définissent la même clé, ce qui est justement le cas normal
     * pour un réglage éditable par branche.</p>
     */
    private String modele(String cle, UUID branchId, String defaut) {
        Optional<String> propreALaBranche = (branchId == null)
                ? Optional.empty()
                : settingAppRepository.findByKeyAndBranchId(cle, branchId)
                        .map(SettingApp::getValue)
                        .filter(valeur -> !valeur.isBlank());

        return propreALaBranche
                .or(() -> settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(cle)).stream()
                        .map(SettingApp::getValue)
                        .filter(valeur -> valeur != null && !valeur.isBlank())
                        .findFirst())
                .orElse(defaut);
    }

    /**
     * Remplace les jetons du modèle.
     *
     * <p>Le remplacement est littéral, et non un {@code String.format} : le texte
     * vient de l'écran Paramètres, où un {@code %} saisi par un utilisateur ferait
     * échouer le formatage et perdre le SMS.</p>
     */
    private String remplacer(String modele, Map<String, String> jetons) {
        String resultat = modele;
        for (Map.Entry<String, String> jeton : jetons.entrySet()) {
            resultat = resultat.replace(jeton.getKey(), jeton.getValue());
        }
        return resultat;
    }
}

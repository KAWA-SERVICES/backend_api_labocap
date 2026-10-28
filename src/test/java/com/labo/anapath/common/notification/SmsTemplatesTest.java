package com.labo.anapath.common.notification;

import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// smsFacture lit aussi le numéro de rappel : les tests qui ne le règlent pas
// laissent cette lecture à la réponse par défaut (aucun réglage).
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsTemplatesTest {

    @Mock private SettingAppRepository settingAppRepository;
    @InjectMocks private SmsTemplates templates;

    private final UUID BRANCHE = UUID.randomUUID();

    private SettingApp setting(String valeur) {
        SettingApp s = new SettingApp();
        s.setValue(valeur);
        return s;
    }

    private void aucunReglage() {
        lenient().when(settingAppRepository.findByKeyAndBranchId(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("aucun réglage → le message livré est utilisé")
    void sansReglage_utiliseLeDefaut() {
        aucunReglage();
        assertThat(templates.smsResultat(BRANCHE)).isEqualTo(SmsTemplates.DEFAUT_SMS_RESULTAT);
    }

    @Test
    @DisplayName("texte paramétré sur la branche → il l'emporte sur le défaut")
    void reglageDeLaBranche_lEmporte() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_RESULTAT, BRANCHE))
                .thenReturn(Optional.of(setting("Vos résultats sont prêts.")));

        assertThat(templates.smsResultat(BRANCHE)).isEqualTo("Vos résultats sont prêts.");
        // La branche a répondu : inutile d'aller chercher celle de la maison mère.
        verify(settingAppRepository, never()).findByKeyInOrderByCreatedAtAsc(anyCollection());
    }

    @Test
    @DisplayName("antenne sans texte propre → celui de la branche mère est repris")
    void sansTexteDeBranche_repliSurLaBrancheMere() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_RESULTAT, BRANCHE))
                .thenReturn(Optional.empty());
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(SmsTemplates.CLE_SMS_RESULTAT)))
                .thenReturn(List.of(setting("Texte de la maison mère"), setting("Texte d'une autre antenne")));

        assertThat(templates.smsResultat(BRANCHE)).isEqualTo("Texte de la maison mère");
    }

    @Test
    @DisplayName("champ vidé depuis Paramètres → le message livré reprend la main")
    void reglageVide_retombeSurLeDefaut() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("   ")));
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());

        assertThat(templates.smsFacture(BRANCHE, "FA260001", "EX26-0001", "https://x/y"))
                .isEqualTo("Dossier EX26-0001: resultat dans 3 semaines. Facture: https://x/y");
    }

    @Test
    @DisplayName("jetons {code} et {lien} remplacés dans le texte paramétré, tiret typographique ramené à l'ASCII")
    void jetons_sontRemplaces() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("Facture {code} : {lien} — merci")));

        assertThat(templates.smsFacture(BRANCHE, "FA260007", "EX26-0007", "https://api.caap.bj/f/jeton"))
                .isEqualTo("Facture FA260007 : https://api.caap.bj/f/jeton - merci");
    }

    @Test
    @DisplayName("un % saisi dans Paramètres ne casse pas l'envoi")
    void pourcentDansLeTexte_neCassePas() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("Remise 100% appliquée. Facture {code} : {lien}")));

        assertThat(templates.smsFacture(BRANCHE, "FA260008", "EX26-0008", "https://x/y"))
                .isEqualTo("Remise 100% appliquee. Facture FA260008 : https://x/y");
    }

    @Test
    @DisplayName("facture sans code → le jeton disparaît, aucun « null » dans le SMS")
    void codeAbsent_neLaissePasNull() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("Facture {code} : {lien}")));

        assertThat(templates.smsFacture(BRANCHE, null, "EX26-0009", "https://x/y"))
                .isEqualTo("Facture  : https://x/y")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("texte livré : une seule unité SMS, sans accent, avec un lien court réaliste")
    void texteLivre_uneUniteSansAccent() {
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(anyCollection())).thenReturn(List.of());

        String sms = templates.smsFacture(BRANCHE, "FA260001", "EX26-0001", "https://api.caap.bj/f/Ab3dE5fG7hJ9");

        assertThat(sms).isEqualTo("Dossier EX26-0001: resultat dans 3 semaines. Facture: https://api.caap.bj/f/Ab3dE5fG7hJ9")
                .hasSizeLessThanOrEqualTo(SmsTemplates.SMS_UNITE)
                .matches("\\p{ASCII}+");
    }

    @Test
    @DisplayName("texte paramétré accentué → accents retirés, pour rester à une unité")
    void texteAccentue_accentsRetires() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("Dossier {demande} : résultat prêt à récupérer. {lien}")));

        assertThat(templates.smsFacture(BRANCHE, "FA260001", "EX26-0001", "https://x/y"))
                .isEqualTo("Dossier EX26-0001 : resultat pret a recuperer. https://x/y");
    }

    @Test
    @DisplayName("texte paramétré au-delà d'une unité → texte livré")
    void texteTropLong_texteLivre() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_FACTURE, BRANCHE))
                .thenReturn(Optional.of(setting("x".repeat(200) + " {lien}")));

        assertThat(templates.smsFacture(BRANCHE, "FA260001", "EX26-0001", "https://x/y"))
                .isEqualTo("Dossier EX26-0001: resultat dans 3 semaines. Facture: https://x/y");
    }

    @Test
    @DisplayName("facture sans demande d'examen → variante courte avec le code de facture")
    void sansDemande_varianteCourte() {
        assertThat(templates.smsFacture(BRANCHE, "FA260003", null, "https://x/y"))
                .isEqualTo("Facture FA260003: https://x/y");
    }

    @Test
    @DisplayName("numéro de rappel paramétré → le SMS demande de l'enregistrer, et tient en une unité")
    void numeroDeRappel_ajouteEtUneUnite() {
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(SmsTemplates.CLE_NUMERO_RAPPEL)))
                .thenReturn(List.of(setting("+2290197000001")));

        // Code de demande réel (26-4099) et lien court réaliste (jeton de 12 caractères).
        String sms = templates.smsFacture(BRANCHE, "FA260001", "26-4099", "https://api.caap.bj/f/Ab3dE5fG7hJ9");

        assertThat(sms).isEqualTo("Dossier 26-4099: resultat sous 3 semaines. Facture: https://api.caap.bj/f/Ab3dE5fG7hJ9 "
                        + "Enregistrez le +2290197000001, il vous appellera au resultat")
                .hasSizeLessThanOrEqualTo(SmsTemplates.SMS_UNITE)
                .matches("\\p{ASCII}+");
    }

    @Test
    @DisplayName("numéro de rappel + lien trop long → texte sans rappel, jamais deux SMS")
    void numeroDeRappel_tropLong_texteSansRappel() {
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(SmsTemplates.CLE_NUMERO_RAPPEL)))
                .thenReturn(List.of(setting("+2290197000001")));
        String lien = "https://api.caap.bj/f/" + "a".repeat(60);

        assertThat(templates.smsFacture(BRANCHE, "FA260001", "26-4099", lien))
                .isEqualTo("Dossier 26-4099: resultat dans 3 semaines. Facture: " + lien)
                .hasSizeLessThanOrEqualTo(SmsTemplates.SMS_UNITE);
    }
}

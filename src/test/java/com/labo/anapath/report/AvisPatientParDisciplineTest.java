package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.common.notification.SmsTemplates;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'avis « résultat disponible » suit la discipline du compte-rendu : le texte
 * d'anatomie pathologique est inchangé, la biologie a le sien.
 */
class AvisPatientParDisciplineTest {

    private static final UUID BRANCHE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final SettingAppRepository settingAppRepository = mock(SettingAppRepository.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final SmsTemplates templates = new SmsTemplates(settingAppRepository);
    // Sans audio de rappel paramétré (mock), l'appel ne part pas : seul le SMS
    // est en jeu ici.
    private final NotificationServiceImpl service = new NotificationServiceImpl(reportRepository,
            mock(AppelByReportRepository.class), mock(TestOrderRepository.class), mock(LogReportRepository.class),
            templates, mock(UserRepository.class), mock(FluidChatClient.class), mock(ReglagesDeRappel.class),
            smsSender);

    private Report compteRendu(Discipline discipline) {
        Patient patient = new Patient();
        patient.setTelephone1("97000000");
        TestOrder bon = new TestOrder();
        bon.setPatient(patient);
        Report r = new Report();
        r.setId(UUID.randomUUID());
        r.setBranchId(BRANCHE);
        r.setTestOrder(bon);
        r.setDiscipline(discipline);
        when(reportRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private void aucunTexteParametre() {
        when(settingAppRepository.findByKeyAndBranchId(any(), any())).thenReturn(Optional.empty());
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(anyCollection())).thenReturn(List.of());
    }

    private static SettingApp reglage(String valeur) {
        SettingApp s = new SettingApp();
        s.setValue(valeur);
        return s;
    }

    @Test
    @DisplayName("biologie : « vos résultats d'analyses sont disponibles », pas le texte d'anatomie pathologique")
    void biologie() {
        aucunTexteParametre();
        Report r = compteRendu(Discipline.BIOLOGY);

        NotifyResponseDto resultat = service.notifyPatient(r.getId(), USER);

        assertThat(resultat.channel()).isEqualTo("SMS");
        verify(smsSender, times(1)).envoyer(any(), eq(SmsTemplates.DEFAUT_SMS_RESULTAT_BIOLOGIE), any(), any());
        assertThat(SmsTemplates.DEFAUT_SMS_RESULTAT_BIOLOGIE)
                .contains("vos résultats d'analyses sont disponibles")
                .doesNotContainIgnoringCase("pathologi");
        assertThat(SmsTemplates.DEFAUT_SMS_RESULTAT_BIOLOGIE.length()).isLessThanOrEqualTo(160);
    }

    @Test
    @DisplayName("anatomie pathologique : texte d'origine, inchangé")
    void anatomiePathologique() {
        aucunTexteParametre();
        Report r = compteRendu(Discipline.PATHOLOGY);

        service.notifyPatient(r.getId(), USER);

        verify(smsSender).envoyer(any(), eq(SmsTemplates.DEFAUT_SMS_RESULTAT), any(), any());
    }

    @Test
    @DisplayName("biologie : texte paramétré sous sa propre clé ; celui d'anatomie pathologique n'est jamais repris")
    void biologieParametree() {
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_RESULTAT_BIOLOGIE, BRANCHE))
                .thenReturn(Optional.of(reglage("Labo X : vos analyses sont prêtes.")));
        when(settingAppRepository.findByKeyAndBranchId(SmsTemplates.CLE_SMS_RESULTAT, BRANCHE))
                .thenReturn(Optional.of(reglage("Cabinet d'anatomie pathologique…")));
        Report r = compteRendu(Discipline.BIOLOGY);

        service.sendSms(r.getId(), USER);

        verify(smsSender).envoyer(any(), eq("Labo X : vos analyses sont prêtes."), any(), any());
        verify(settingAppRepository, never()).findByKeyAndBranchId(SmsTemplates.CLE_SMS_RESULTAT, BRANCHE);
    }

    @Test
    @DisplayName("biologie : sans texte de branche, repli sur la branche mère puis sur le défaut de biologie")
    void biologieRepli() {
        when(settingAppRepository.findByKeyAndBranchId(any(), any())).thenReturn(Optional.empty());
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(SmsTemplates.CLE_SMS_RESULTAT_BIOLOGIE)))
                .thenReturn(List.of(reglage("Texte biologie de la maison mère")));
        assertThat(templates.smsResultatBiologie(BRANCHE)).isEqualTo("Texte biologie de la maison mère");

        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of(SmsTemplates.CLE_SMS_RESULTAT_BIOLOGIE)))
                .thenReturn(List.of(reglage("  ")));
        assertThat(templates.smsResultatBiologie(BRANCHE)).isEqualTo(SmsTemplates.DEFAUT_SMS_RESULTAT_BIOLOGIE);
    }
}

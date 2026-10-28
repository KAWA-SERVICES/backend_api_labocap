package com.labo.anapath.report;

import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.common.notification.SmsTemplates;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// Préparation commune (réglages, utilisateur) que chaque test ne sollicite pas en entier.
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock private ReportRepository reportRepository;
    @Mock private AppelByReportRepository appelByReportRepository;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private LogReportRepository logReportRepository;
    @Mock private UserRepository userRepository;
    @Mock private FluidChatClient fluidChatClient;
    @Mock private ReglagesDeRappel reglagesDeRappel;
    @Mock private SmsSender smsSender;
    @Mock private SmsTemplates smsTemplates;

    @InjectMocks
    private NotificationServiceImpl service;

    private final UUID REPORT_ID = UUID.randomUUID();
    private final UUID USER_ID = UUID.randomUUID();
    private final UUID BRANCH_ID = UUID.randomUUID();

    @BeforeEach
    void reglages() {
        when(reglagesDeRappel.audio(any())).thenReturn("custom/2");
        when(reglagesDeRappel.plage()).thenReturn(ReglagesDeRappel.Plage.REGLEMENTAIRE);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(fluidChatClient.lancer(anyString(), anyString(), any(), any()))
                .thenReturn(new FluidChatClient.Appel(57, "queued", null, 0, 0));
    }

    private Report report(String telephone1, String telephone2) {
        Patient patient = new Patient();
        patient.setTelephone1(telephone1);
        patient.setTelephone2(telephone2);
        patient.setLangue("fon");

        TestOrder order = new TestOrder();
        order.setPatient(patient);
        order.setCode("26-4099");

        Report report = new Report();
        report.setId(REPORT_ID);
        report.setBranchId(BRANCH_ID);
        report.setTestOrder(order);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        return report;
    }

    @Test
    @DisplayName("callPatient : appel FluidChat au +229, audio de la langue, plage réglementaire, code de la demande")
    void callPatient_lanceLAppelFluidChat() {
        report("97000001", null);

        CallResponseDto result = service.callPatient(REPORT_ID, USER_ID);

        verify(reglagesDeRappel).audio("fon");
        verify(fluidChatClient).lancer(eq("+22997000001"), eq("custom/2"),
                eq(ReglagesDeRappel.Plage.REGLEMENTAIRE.versCallWindow()), eq("26-4099"));
        assertThat(result.appelId()).isEqualTo("57");
    }

    @Test
    @DisplayName("callPatient : une nouvelle ligne d'historique par appel, en cours, et l'avis marqué donné")
    void callPatient_ajouteUneLigneDHistorique() {
        Report report = report("97000001", null);

        service.callPatient(REPORT_ID, USER_ID);

        ArgumentCaptor<AppelByReport> ligne = ArgumentCaptor.forClass(AppelByReport.class);
        verify(appelByReportRepository).save(ligne.capture());
        assertThat(ligne.getValue().getId()).isNull();
        assertThat(ligne.getValue().getAppelId()).isEqualTo("57");
        assertThat(ligne.getValue().getIssue()).isEqualTo(IssueRappel.EN_COURS);
        assertThat(ligne.getValue().getBranchId()).isEqualTo(BRANCH_ID);
        assertThat(report.getPatientNotifiedAt()).isNotNull();
        assertThat(report.getTestOrder().getStatusAppel()).isEqualTo("57");
    }

    @Test
    @DisplayName("callPatient : sans audio paramétré, aucun appel ne part")
    void callPatient_sansAudio_refuse() {
        report("97000001", null);
        when(reglagesDeRappel.audio(any())).thenReturn(null);

        assertThatThrownBy(() -> service.callPatient(REPORT_ID, USER_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(fluidChatClient, never()).lancer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("callPatient : un appel déjà en cours n'est pas doublé par un second clic")
    void callPatient_appelEnCours_refuse() {
        report("97000001", null);
        AppelByReport enCours = new AppelByReport();
        enCours.setIssue(IssueRappel.EN_COURS);
        when(appelByReportRepository.findFirstByReportIdOrderByCreatedAtDesc(REPORT_ID))
                .thenReturn(Optional.of(enCours));

        assertThatThrownBy(() -> service.callPatient(REPORT_ID, USER_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(fluidChatClient, never()).lancer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("callPatient : rapport inexistant → ResourceNotFoundException")
    void callPatient_unknownReport_throws() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.callPatient(REPORT_ID, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("notifyPatient : SMS puis appel, pour tous les patients")
    void notifyPatient_smsEtAppel() {
        report("97000001", null);

        NotifyResponseDto result = service.notifyPatient(REPORT_ID, USER_ID);

        verify(smsSender).envoyer(eq("22997000001"), any(), eq(SmsSender.SOURCE_RESULTAT), any());
        verify(fluidChatClient).lancer(any(), any(), any(), any());
        assertThat(result.channel()).isEqualTo("CALL");
        assertThat(result.message()).isEqualTo("SMS envoyé et appel programmé");
    }

    @Test
    @DisplayName("notifyPatient : FluidChat en panne, le SMS suffit à prévenir")
    void notifyPatient_appelEnEchec_smsSeul() {
        report("97000001", null);
        when(fluidChatClient.lancer(any(), any(), any(), any())).thenThrow(new ExternalApiException("panne"));

        NotifyResponseDto result = service.notifyPatient(REPORT_ID, USER_ID);

        assertThat(result.channel()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("notifyPatient : ni SMS ni appel, l'erreur remonte pour que la tâche réessaie")
    void notifyPatient_toutEnEchec_leve() {
        Report report = report("97000001", null);
        when(fluidChatClient.lancer(any(), any(), any(), any())).thenThrow(new ExternalApiException("panne"));
        doThrow(new ExternalApiException("sms")).when(smsSender).envoyer(any(), any(), any(), any());

        assertThatThrownBy(() -> service.notifyPatient(REPORT_ID, USER_ID))
                .isInstanceOf(ExternalApiException.class);
        assertThat(report.getPatientNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("notifyPatient : téléphone 2 utilisé quand le premier manque")
    void notifyPatient_telephone2() {
        report("  ", "66000002");

        service.notifyPatient(REPORT_ID, USER_ID);

        verify(fluidChatClient).lancer(eq("+22966000002"), any(), any(), any());
    }

    @Test
    @DisplayName("notifyPatient : patient sans numéro → ligne SANS_NUMERO, rien n'est envoyé, pas de nouvel essai")
    void notifyPatient_sansNumero() {
        Report report = report(null, null);

        NotifyResponseDto result = service.notifyPatient(REPORT_ID, USER_ID);

        assertThat(result.channel()).isEqualTo("NONE");
        ArgumentCaptor<AppelByReport> ligne = ArgumentCaptor.forClass(AppelByReport.class);
        verify(appelByReportRepository).save(ligne.capture());
        assertThat(ligne.getValue().getIssue()).isEqualTo(IssueRappel.SANS_NUMERO);
        assertThat(ligne.getValue().getAppelId()).isNull();
        assertThat(report.getPatientNotifiedAt()).isNotNull();
        verify(smsSender, never()).envoyer(any(), any(), any(), any());
        verify(fluidChatClient, never()).lancer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("suivi en direct : un appel en cours est relu chez FluidChat ; joint, le patient est marqué informé")
    void getAppelStatus_relitEtMarqueInforme() {
        Report report = report("97000001", null);
        AppelByReport appel = new AppelByReport();
        appel.setReport(report);
        appel.setAppelId("57");
        appel.setIssue(IssueRappel.EN_COURS);
        when(appelByReportRepository.findFirstByReportIdOrderByCreatedAtDesc(REPORT_ID)).thenReturn(Optional.of(appel));
        when(fluidChatClient.lire(57)).thenReturn(new FluidChatClient.Appel(57, "answered", "completed", 100, 1));

        AppelResponseDto dto = service.getAppelStatus(REPORT_ID);

        assertThat(dto.issue()).isEqualTo(IssueRappel.JOINT);
        assertThat(report.isCalled()).isTrue();
        verify(reportRepository).save(report);
    }

    @Test
    @DisplayName("suivi en direct : FluidChat injoignable, l'écran reçoit le dernier état connu")
    void getAppelStatus_panne_dernierEtat() {
        AppelByReport appel = new AppelByReport();
        appel.setAppelId("57");
        appel.setIssue(IssueRappel.EN_COURS);
        when(appelByReportRepository.findFirstByReportIdOrderByCreatedAtDesc(REPORT_ID)).thenReturn(Optional.of(appel));
        when(fluidChatClient.lire(57)).thenThrow(new ExternalApiException("panne"));

        assertThat(service.getAppelStatus(REPORT_ID).issue()).isEqualTo(IssueRappel.EN_COURS);
    }

    @Test
    @DisplayName("appliquer : un appel terminé reçoit son issue et sa date de fin")
    void appliquer_appelTermine() {
        AppelByReport appel = new AppelByReport();

        NotificationServiceImpl.appliquer(appel, new FluidChatClient.Appel(57, "answered", "partial", 74, 1));

        assertThat(appel.getIssue()).isEqualTo(IssueRappel.JOINT);
        assertThat(appel.getListenedPct()).isEqualTo(74);
        assertThat(appel.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("sendSms : clé d'idempotence tirée au sort, pour que la relance passe")
    void sendSms_cleDIdempotenceVariableEntreDeuxEnvois() {
        // Le renvoi manuel d'un avis est un besoin de l'écran de suivi : une clé
        // stable ferait écarter le second envoi comme un doublon.
        report("97000004", null);

        service.sendSms(REPORT_ID, USER_ID);
        service.sendSms(REPORT_ID, USER_ID);

        ArgumentCaptor<UUID> cles = ArgumentCaptor.forClass(UUID.class);
        verify(smsSender, times(2)).envoyer(any(), any(), eq(SmsSender.SOURCE_RESULTAT), cles.capture());
        assertThat(cles.getAllValues().get(0)).isNotEqualTo(cles.getAllValues().get(1));
    }

    @Test
    @DisplayName("Plage.versCallWindow : jours triés et fuseau du laboratoire")
    void plage_callWindow() {
        assertThat(ReglagesDeRappel.Plage.REGLEMENTAIRE.versCallWindow()).isEqualTo(Map.of(
                "start", "08:00", "end", "18:00", "days", java.util.List.of(1, 2, 3, 4, 5),
                "timezone", "Africa/Porto-Novo"));
    }
}

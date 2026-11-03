package com.labo.anapath.report;

import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le rappel planifié agit sans utilisateur : sa ligne de journal n'a pas
 * d'auteur. Quand log_reports.user_id l'interdisait, toute la transaction était
 * annulée après le départ de l'appel — patient non marqué prévenu, rappelé à
 * chaque passage de la tâche (prod CAAP, 28/10/2026 : jusqu'à 39 appels par
 * patient dans la journée).
 */
@SpringBootTest
@Testcontainers
class RappelAutomatiqueIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        // La tâche est appelée à la main : pas de passage planifié pendant le test.
        registry.add("app.report.notify-retry.cron", () -> "-");
        registry.add("app.report.call-follow.cron", () -> "-");
        registry.add("app.report.call-retry.cron", () -> "-");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Les jobs testés ici balaient toutes les lignes dues, sans filtrer sur le
     * compte-rendu du test : sans nettoyage, le compte-rendu d'un test précédent
     * (même conteneur Postgres, pas de rollback) se fait rappeler une seconde
     * fois par le test suivant.
     */
    @AfterEach
    void nettoyerLesDonnees() {
        jdbc.update("DELETE FROM appel_by_reports");
        jdbc.update("DELETE FROM log_reports");
        jdbc.update("DELETE FROM reports");
        jdbc.update("DELETE FROM test_orders");
        jdbc.update("DELETE FROM patients");
    }

    /** Plage toujours ouverte : ces tests ne portent pas sur la fenêtre horaire (déjà couverte par RattrapageAvisPatientJobTest), qui ferait échouer les relances hors 8h–18h en semaine. */
    private static final ReglagesDeRappel.Plage TOUJOURS_OUVERT =
            new ReglagesDeRappel.Plage(LocalTime.MIN, LocalTime.MAX, Set.of(0, 1, 2, 3, 4, 5, 6));

    @MockitoBean private FluidChatClient fluidChatClient;
    @MockitoBean private SmsSender smsSender;
    @MockitoBean private ReglagesDeRappel reglagesDeRappel;

    @Autowired private RattrapageAvisPatientJob job;
    @Autowired private PatientRepository patientRepository;
    @Autowired private TestOrderRepository testOrderRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private AppelByReportRepository appelRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("rappel planifié : un seul appel, patient marqué prévenu, journal sans auteur")
    void unSeulAppelParPatient() {
        when(reglagesDeRappel.delaiHeures()).thenReturn(24);
        when(reglagesDeRappel.audio(any())).thenReturn("custom/16");
        when(reglagesDeRappel.plage()).thenReturn(TOUJOURS_OUVERT);
        when(fluidChatClient.lancer(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FluidChatClient.Appel(89, "queued", null, null, 0));
        Report report = compteRenduValideIlYA30h();

        job.rattraperAvisEnAttente();
        job.rattraperAvisEnAttente();

        verify(fluidChatClient, times(1)).lancer(anyString(), anyString(), any(), anyString());
        assertThat(reportRepository.findById(report.getId()).orElseThrow().getPatientNotifiedAt()).isNotNull();
        assertThat(appelRepository.findFirstByReportIdOrderByCreatedAtDesc(report.getId()))
                .hasValueSatisfying(a -> assertThat(a.getAppelId()).isEqualTo("89"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM log_reports WHERE report_id = ? AND user_id IS NULL",
                Integer.class, report.getId())).isPositive();
    }

    @Test
    @DisplayName("suivi : un appel joint est relu une fois, le patient est marqué informé")
    void appelJointReluUneFois() {
        when(reglagesDeRappel.delaiHeures()).thenReturn(24);
        when(reglagesDeRappel.audio(any())).thenReturn("custom/16");
        when(reglagesDeRappel.plage()).thenReturn(TOUJOURS_OUVERT);
        when(fluidChatClient.lancer(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FluidChatClient.Appel(302, "queued", null, null, 0));
        when(fluidChatClient.lire(302L))
                .thenReturn(new FluidChatClient.Appel(302, "answered", "completed", 100, 1));
        Report report = compteRenduValideIlYA30h("26-3803");
        job.rattraperAvisEnAttente();

        job.suivreLesAppels();
        job.suivreLesAppels();

        verify(fluidChatClient, times(1)).lire(302L);
        assertThat(appelRepository.findFirstByReportIdOrderByCreatedAtDesc(report.getId()))
                .hasValueSatisfying(a -> assertThat(a.getIssue()).isEqualTo(IssueRappel.JOINT));
        assertThat(reportRepository.findById(report.getId()).orElseThrow().isCalled()).isTrue();
    }

    @Test
    @DisplayName("relance : un patient sans réponse n'est pas rerappelé avant l'intervalle de relance")
    void pasDeRelanceAvantLIntervalle() {
        when(reglagesDeRappel.plage()).thenReturn(TOUJOURS_OUVERT);
        when(reglagesDeRappel.intervalleRelanceMinutes()).thenReturn(20);
        when(reglagesDeRappel.tentativesMax()).thenReturn(3);
        Report report = compteRenduValideIlYA30h("26-9001");
        appelTermine(report, IssueRappel.SANS_REPONSE, LocalDateTime.now().minusMinutes(5));

        job.relancerLesAppelsSansReponse();

        verify(fluidChatClient, times(0)).lancer(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("relance : un patient sans réponse est rerappelé passé l'intervalle, nouvelle ligne d'historique")
    void relanceApresLIntervalle() {
        when(reglagesDeRappel.audio(any())).thenReturn("custom/16");
        when(reglagesDeRappel.plage()).thenReturn(TOUJOURS_OUVERT);
        when(reglagesDeRappel.intervalleRelanceMinutes()).thenReturn(20);
        when(reglagesDeRappel.tentativesMax()).thenReturn(3);
        when(fluidChatClient.lancer(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FluidChatClient.Appel(90, "queued", null, null, 0));
        Report report = compteRenduValideIlYA30h("26-9002");
        appelTermine(report, IssueRappel.SANS_REPONSE, LocalDateTime.now().minusMinutes(25));

        job.relancerLesAppelsSansReponse();

        verify(fluidChatClient, times(1)).lancer(anyString(), anyString(), any(), anyString());
        assertThat(appelRepository.findAll().stream()
                .filter(a -> report.getId().equals(a.getReport().getId())))
                .hasSize(2);
    }

    @Test
    @DisplayName("relance : plus aucune relance automatique au plafond de tentatives")
    void aucuneRelanceAuPlafond() {
        when(reglagesDeRappel.plage()).thenReturn(TOUJOURS_OUVERT);
        when(reglagesDeRappel.intervalleRelanceMinutes()).thenReturn(20);
        when(reglagesDeRappel.tentativesMax()).thenReturn(2);
        Report report = compteRenduValideIlYA30h("26-9003");
        appelTermine(report, IssueRappel.SANS_REPONSE, LocalDateTime.now().minusMinutes(60));
        appelTermine(report, IssueRappel.SANS_REPONSE, LocalDateTime.now().minusMinutes(25));

        job.relancerLesAppelsSansReponse();

        verify(fluidChatClient, times(0)).lancer(anyString(), anyString(), any(), anyString());
    }

    private AppelByReport appelTermine(Report report, IssueRappel issue, LocalDateTime finishedAt) {
        AppelByReport appel = new AppelByReport();
        appel.setBranchId(report.getBranchId());
        appel.setReport(report);
        appel.setIssue(issue);
        appel.setFinishedAt(finishedAt);
        return appelRepository.save(appel);
    }

    private Report compteRenduValideIlYA30h() {
        return compteRenduValideIlYA30h("26-3667");
    }

    private Report compteRenduValideIlYA30h(String code) {
        Patient patient = new Patient();
        patient.setBranchId(AGENCE);
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        patient.setTelephone1("0197" + code.replace("-", ""));
        patient = patientRepository.save(patient);

        TestOrder bon = new TestOrder();
        bon.setBranchId(AGENCE);
        bon.setStatus(TestOrderStatus.VALIDATED);
        bon.setPrelevementDate(LocalDate.now());
        bon.setPatient(patient);
        bon.setCode(code);
        bon = testOrderRepository.save(bon);

        Report r = new Report();
        r.setBranchId(AGENCE);
        r.setTestOrder(bon);
        r.setCode("CO" + code);
        r.setStatus(ReportStatus.VALIDATED);
        r.setSignatureDate(LocalDateTime.now().minusHours(30));
        return reportRepository.save(r);
    }
}

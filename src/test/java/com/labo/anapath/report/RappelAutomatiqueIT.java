package com.labo.anapath.report;

import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
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
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(reglagesDeRappel.plage()).thenReturn(ReglagesDeRappel.Plage.REGLEMENTAIRE);
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

    private Report compteRenduValideIlYA30h() {
        Patient patient = new Patient();
        patient.setBranchId(AGENCE);
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        patient.setTelephone1("0161157392");
        patient = patientRepository.save(patient);

        TestOrder bon = new TestOrder();
        bon.setBranchId(AGENCE);
        bon.setStatus(TestOrderStatus.VALIDATED);
        bon.setPrelevementDate(LocalDate.now());
        bon.setPatient(patient);
        bon.setCode("26-3667");
        bon = testOrderRepository.save(bon);

        Report r = new Report();
        r.setBranchId(AGENCE);
        r.setTestOrder(bon);
        r.setCode("CO26-3667");
        r.setStatus(ReportStatus.VALIDATED);
        r.setSignatureDate(LocalDateTime.now().minusHours(30));
        return reportRepository.save(r);
    }
}

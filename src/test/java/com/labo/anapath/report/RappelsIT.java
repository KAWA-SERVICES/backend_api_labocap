package com.labo.anapath.report;

import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.role.Role;
import com.labo.anapath.role.RoleRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import com.labo.anapath.testsupport.Jetons;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Historique des rappels : compteurs du tableau de bord, bandeau « à rappeler »
 * et filtre du suivi lisent les requêtes natives sur {@code appel_by_reports},
 * cloisonnées par agence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class RappelsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        // Les tâches planifiées n'ont rien à faire pendant le test.
        registry.add("app.report.notify-retry.cron", () -> "-");
        registry.add("app.report.call-follow.cron", () -> "-");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTRE_AGENCE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String ADMIN = "admin_rappels@labo.bj";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private TestOrderRepository testOrderRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private AppelByReportRepository appelRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Jetons jetons;
    @LocalServerPort private int port;

    private Report nonJoint;

    @BeforeEach
    void jeuDeDonnees() {
        if (userRepository.findByEmail(ADMIN).isPresent()) {
            return;
        }
        Role admin = roleRepository.findBySlugAndBranchId("admin", AGENCE).orElseThrow();
        User u = new User();
        u.setBranchId(AGENCE);
        u.setFirstname("Test");
        u.setLastname("RAPPELS");
        u.setEmail(ADMIN);
        u.setPassword(passwordEncoder.encode("Vq7-tz9Lp2-Xw4"));
        u.setActive(true);
        u.setRoles(List.of(admin));
        userRepository.save(u);
        jdbc.update("INSERT INTO branches (id, name, code, location, created_at, updated_at) "
                + "VALUES (?, 'Antenne', 'ANT', 'Parakou', NOW(), NOW()) ON CONFLICT DO NOTHING", AUTRE_AGENCE);

        // Joint au premier appel, puis rappelé à la main sans réponse : seul le
        // dernier appel compte — le patient est à rappeler.
        nonJoint = compteRendu(AGENCE, "26-0001");
        appel(nonJoint, IssueRappel.JOINT, LocalDateTime.now().minusHours(3));
        appel(nonJoint, IssueRappel.SANS_REPONSE, LocalDateTime.now().minusHours(1));
        // Joint : rien à faire.
        appel(compteRendu(AGENCE, "26-0002"), IssueRappel.JOINT, LocalDateTime.now().minusHours(1));
        // Sans numéro, mais déjà marqué informé par le secrétariat : sort de la liste.
        Report informe = compteRendu(AGENCE, "26-0003");
        informe.setCalled(true);
        reportRepository.save(informe);
        appel(informe, IssueRappel.SANS_NUMERO, LocalDateTime.now().minusHours(1));
        // Autre agence : invisible d'ici.
        appel(compteRendu(AUTRE_AGENCE, "26-0004"), IssueRappel.INJOIGNABLE, LocalDateTime.now().minusHours(1));
    }

    private Report compteRendu(UUID agence, String code) {
        Patient patient = new Patient();
        patient.setBranchId(agence);
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        patient.setTelephone1("97000001");
        patient = patientRepository.save(patient);

        TestOrder bon = new TestOrder();
        bon.setBranchId(agence);
        bon.setStatus(TestOrderStatus.VALIDATED);
        bon.setPrelevementDate(LocalDate.now());
        bon.setPatient(patient);
        bon.setCode(code);
        bon = testOrderRepository.save(bon);

        Report r = new Report();
        r.setBranchId(agence);
        r.setTestOrder(bon);
        r.setCode("CO" + code);
        r.setStatus(ReportStatus.VALIDATED);
        r.setSignatureDate(LocalDateTime.now().minusDays(1));
        return reportRepository.save(r);
    }

    private void appel(Report report, IssueRappel issue, LocalDateTime le) {
        AppelByReport a = new AppelByReport();
        a.setBranchId(report.getBranchId());
        a.setReport(report);
        a.setAppelId(issue == IssueRappel.SANS_NUMERO ? null : String.valueOf(le.getNano()));
        a.setIssue(issue);
        a = appelRepository.save(a);
        // created_at est posé à l'enregistrement : on le recule pour ordonner les appels.
        jdbc.update("UPDATE appel_by_reports SET created_at = ? WHERE id = ?", le, a.getId());
    }

    private <T> T get(String chemin, ParameterizedTypeReference<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(ADMIN));
        ResponseEntity<T> reponse = rest.exchange("http://localhost:" + port + "/api/v1" + chemin,
                HttpMethod.GET, new HttpEntity<>(h), type);
        assertThat(reponse.getStatusCode().is2xxSuccessful()).as(chemin + " → " + reponse.getStatusCode()).isTrue();
        return reponse.getBody();
    }

    @Test
    @DisplayName("tableau de bord : dernier appel de chaque compte rendu, agence courante seulement")
    @SuppressWarnings("unchecked")
    void tableauDeBord() {
        Map<String, Object> data = (Map<String, Object>) get("/dashboard/rappels",
                new ParameterizedTypeReference<Map<String, Object>>() {}).get("data");

        Map<String, Number> parIssue = (Map<String, Number>) data.get("parIssue");
        assertThat(parIssue.get("JOINT").longValue()).isEqualTo(1);
        assertThat(parIssue.get("SANS_REPONSE").longValue()).isEqualTo(1);
        assertThat(parIssue.get("SANS_NUMERO").longValue()).isEqualTo(1);
        assertThat(parIssue.get("INJOIGNABLE").longValue()).isZero();
        assertThat(((Number) data.get("aRappeler")).longValue()).isEqualTo(1);
        // Ni FluidChat ni lien audio dans l'environnement de test : tout est signalé.
        assertThat((List<String>) data.get("reglagesManquants"))
                .contains("fluidchat_api", "fluidchat_audio_fr", "fluidchat_audio_bip");
    }

    @Test
    @DisplayName("suivi ?rappel=A_RAPPELER : seul le patient non joint et non informé, avec son issue")
    @SuppressWarnings("unchecked")
    void suiviARappeler() {
        // size=1 : la page est pleine, Spring Data exécute donc aussi la countQuery.
        Map<String, Object> data = (Map<String, Object>) get(
                "/reports/suivi/list?rappel=A_RAPPELER&page=0&size=1",
                new ParameterizedTypeReference<Map<String, Object>>() {}).get("data");

        List<Map<String, Object>> lignes = (List<Map<String, Object>>) data.get("content");
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).get("testOrderCode")).isEqualTo("26-0001");
        assertThat(lignes.get(0).get("rappelIssue")).isEqualTo("SANS_REPONSE");
        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("suivi : filtre de rappel inconnu refusé")
    void suiviFiltreInconnu() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(ADMIN));
        ResponseEntity<String> reponse = rest.exchange(
                "http://localhost:" + port + "/api/v1/reports/suivi/list?rappel=N_IMPORTE_QUOI",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(reponse.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    @DisplayName("avis manuel sur un compte rendu d'une autre agence → 404, aucun appel ni SMS")
    void avisManuel_autreAgence_404() {
        Report ailleurs = reportRepository.findAll().stream()
                .filter(r -> AUTRE_AGENCE.equals(r.getBranchId())).findFirst().orElseThrow();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(ADMIN));
        for (String action : List.of("/notify", "/call", "/sms")) {
            ResponseEntity<String> reponse = rest.exchange(
                    "http://localhost:" + port + "/api/v1/reports/" + ailleurs.getId() + action,
                    HttpMethod.POST, new HttpEntity<>(h), String.class);
            assertThat(reponse.getStatusCode().value()).as(action).isEqualTo(404);
        }
        ResponseEntity<String> appel = rest.exchange(
                "http://localhost:" + port + "/api/v1/reports/" + ailleurs.getId() + "/appel",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(appel.getStatusCode().value()).isEqualTo(404);
    }
}

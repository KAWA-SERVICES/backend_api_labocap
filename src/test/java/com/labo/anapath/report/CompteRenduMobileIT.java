package com.labo.anapath.report;

import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.role.Role;
import com.labo.anapath.role.RoleRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignment;
import com.labo.anapath.testorder.TestOrderAssignmentDetail;
import com.labo.anapath.testorder.TestOrderAssignmentDetailRepository;
import com.labo.anapath.testorder.TestOrderAssignmentRepository;
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
import org.springframework.http.HttpStatus;
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
 * Le compte rendu vu de l'application mobile : lecture par qui peut le
 * réécrire, destinataire du lot (pour n'offrir « Valider » qu'à lui), et
 * macroscopie complétée par ajout — jamais par remplacement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class CompteRenduMobileIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTRE_AGENCE = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final String LABO = "labo_crmobile@labo.bj";
    private static final String SANS_DROIT = "sansdroit_crmobile@labo.bj";
    private static final String ADMIN = "admin_crmobile@labo.bj";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private TestOrderRepository testOrderRepository;
    @Autowired private TestOrderAssignmentRepository assignmentRepository;
    @Autowired private TestOrderAssignmentDetailRepository detailRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private LogReportRepository logReportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Jetons jetons;
    @LocalServerPort private int port;

    private User labo;

    @BeforeEach
    void comptes() {
        jdbc.update("""
                INSERT INTO branches (id, name, code, location, created_at, updated_at)
                SELECT ?, 'Annexe CR', 'ANXCR', 'Parakou', NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM branches WHERE id = ?)""", AUTRE_AGENCE, AUTRE_AGENCE);
        // Le laborantin tel que la base de reprise le décrit : il réécrit, sans view-reports.
        labo = compte(LABO, "TOSSOU", role("labo_crmobile", List.of("edit-reports")));
        compte(SANS_DROIT, "DOSSOU", role("sansdroit_crmobile", List.of("view-test-orders")));
        compte(ADMIN, "HOUNSOU", roleRepository.findBySlugAndBranchId("admin", AGENCE).orElseThrow());
    }

    private Role role(String slug, List<String> permissions) {
        Role r = roleRepository.findBySlugAndBranchId(slug, AGENCE).orElseGet(() -> {
            Role nouveau = new Role();
            nouveau.setBranchId(AGENCE);
            nouveau.setName(slug);
            nouveau.setSlug(slug);
            return roleRepository.save(nouveau);
        });
        for (String p : permissions) {
            jdbc.update("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT ?, id FROM permissions WHERE slug = ?
                    AND NOT EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions x ON x.id = rp.permission_id
                                    WHERE rp.role_id = ? AND x.slug = ?)""", r.getId(), p, r.getId(), p);
        }
        return r;
    }

    private User compte(String email, String nom, Role role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setBranchId(AGENCE);
            u.setFirstname("Test");
            u.setLastname(nom);
            u.setEmail(email);
            u.setPassword(passwordEncoder.encode("Vq7-tz9Lp2-Xw4"));
            u.setActive(true);
            u.setRoles(List.of(role));
            return userRepository.save(u);
        });
    }

    private Report compteRendu(UUID agence, ReportStatus statut) {
        Patient patient = new Patient();
        patient.setBranchId(agence);
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        patient = patientRepository.save(patient);

        TestOrder bon = new TestOrder();
        bon.setBranchId(agence);
        bon.setStatus(TestOrderStatus.VALIDATED);
        bon.setPrelevementDate(LocalDate.now());
        bon.setPatient(patient);
        bon.setCode("EX-" + UUID.randomUUID().toString().substring(0, 8));
        bon = testOrderRepository.save(bon);

        Report r = new Report();
        r.setBranchId(agence);
        r.setTestOrder(bon);
        r.setCode("CO" + bon.getCode());
        r.setStatus(statut);
        if (statut != ReportStatus.DRAFT) r.setSignatureDate(LocalDateTime.now().minusDays(1));
        r.setContent("<p>Texte <b>d'origine</b></p>");
        r.setContentMicro("<p>Lames d'origine</p>");
        return reportRepository.save(r);
    }

    private String url(String chemin) {
        return "http://localhost:" + port + "/api/v1/reports" + chemin;
    }

    private HttpHeaders enTetes(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(email));
        return h;
    }

    private ResponseEntity<ApiResponse<ReportDetailDto>> lire(Report r, String email) {
        return rest.exchange(url("/" + r.getId()), HttpMethod.GET,
                new HttpEntity<>(enTetes(email)), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<ApiResponse<Void>> ajouter(Report r, String email, String texte) {
        return rest.exchange(url("/" + r.getId() + "/macroscopie"), HttpMethod.POST,
                new HttpEntity<>(Map.of("texte", texte), enTetes(email)), new ParameterizedTypeReference<>() {});
    }

    // ------------------------------------------------------------------ lecture

    @Test
    @DisplayName("edit-reports sans view-reports lit le détail du compte rendu")
    void lecture_avecEditReportsSeul_autorisee() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);
        ResponseEntity<ApiResponse<ReportDetailDto>> reponse = lire(r, LABO);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reponse.getBody().data().content()).isEqualTo("<p>Texte <b>d'origine</b></p>");
    }

    @Test
    @DisplayName("Sans view-reports ni edit-reports → 403, en lecture comme en ajout")
    void sansDroit_refuse() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);
        assertThat(lire(r, SANS_DROIT).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ajouter(r, SANS_DROIT, "Fragment").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Compte rendu d'une autre agence → 404, en lecture comme en ajout, contenu intact")
    void autreAgence_introuvable() {
        Report r = compteRendu(AUTRE_AGENCE, ReportStatus.DRAFT);
        assertThat(lire(r, LABO).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ajouter(r, LABO, "Fragment").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte <b>d'origine</b></p>");
    }

    @Test
    @DisplayName("Le détail nomme le compte du lot : assignedToId")
    void detail_porteLeDestinataireDuLot() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);
        TestOrderAssignment lot = new TestOrderAssignment();
        lot.setBranchId(AGENCE);
        lot.setUser(labo);
        lot.setCode("AF-" + UUID.randomUUID().toString().substring(0, 6));
        lot.setDate(LocalDate.now());
        lot = assignmentRepository.save(lot);
        TestOrderAssignmentDetail ligne = new TestOrderAssignmentDetail();
        ligne.setBranchId(AGENCE);
        ligne.setTestOrderAssignment(lot);
        ligne.setTestOrder(r.getTestOrder());
        ligne.setTestOrderCode(r.getTestOrder().getCode());
        detailRepository.save(ligne);

        ReportDetailDto detail = lire(r, ADMIN).getBody().data();
        assertThat(detail.assignedToId()).isEqualTo(labo.getId());
        assertThat(detail.assignmentCode()).isEqualTo(lot.getCode());
    }

    // ------------------------------------------------------------------ macroscopie

    @Test
    @DisplayName("Ajout : à la suite du contenu, texte échappé en paragraphes, le reste intact, une ligne au journal")
    void macroscopie_ajouteALaSuite() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);

        ResponseEntity<ApiResponse<Void>> reponse =
                ajouter(r, LABO, "  Fragment de 2 cm.\n\n<script>alert(1)</script> & co  \n");
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Report relu = reportRepository.findById(r.getId()).orElseThrow();
        assertThat(relu.getContent()).isEqualTo("<p>Texte <b>d'origine</b></p>"
                + "<p>Fragment de 2 cm.</p>"
                + "<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; co</p>");
        assertThat(relu.getContentMicro()).isEqualTo("<p>Lames d'origine</p>");
        assertThat(relu.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(logReportRepository.findByReportIdOrderByCreatedAtDesc(r.getId()))
                .extracting(LogReport::getAction)
                .containsExactly("Macroscopie complétée (mobile)");
    }

    @Test
    @DisplayName("Ajout sur un compte rendu validé → 422, contenu intact")
    void macroscopie_surValide_refuse() {
        Report r = compteRendu(AGENCE, ReportStatus.VALIDATED);
        assertThat(ajouter(r, LABO, "Fragment").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte <b>d'origine</b></p>");
    }

    @Test
    @DisplayName("Au-delà du plafond cumulé → 422, contenu intact")
    void macroscopie_auDelaDuPlafond_refuse() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);
        String long_ = "x".repeat(ReportServiceImpl.MACROSCOPIE_MAX);
        r.setContent(long_);
        reportRepository.save(r);
        assertThat(ajouter(r, LABO, "Fragment").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent()).isEqualTo(long_);
    }

    @Test
    @DisplayName("Texte vide → 400")
    void macroscopie_vide_refuse() {
        Report r = compteRendu(AGENCE, ReportStatus.DRAFT);
        assertThat(ajouter(r, LABO, "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

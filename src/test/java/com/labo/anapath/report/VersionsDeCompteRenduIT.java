package com.labo.anapath.report;

import com.labo.anapath.common.dto.ApiResponse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
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

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Intégrité des comptes-rendus validés : l'ancien texte survit à la retouche,
 * la retouche d'un compte-rendu livré est réservée à un signataire motivé, et
 * les tables de trace ne se modifient pas.
 *
 * <p>Un seul contexte Spring pour tous ces cas : il coûte vingt secondes.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class VersionsDeCompteRenduIT {

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
    private static final String MEDECIN = "medecin_versions@labo.bj";
    private static final String SECRETAIRE = "secretaire_versions@labo.bj";
    private static final String MOTIF = "Complément demandé par le prescripteur après relecture.";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private TestOrderRepository testOrderRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private ReportVersionRepository reportVersionRepository;
    @Autowired private LogReportRepository logReportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Jetons jetons;
    @LocalServerPort private int port;

    private User medecin;
    private User secretaire;

    @BeforeEach
    void comptes() {
        medecin = compte(MEDECIN, "AHOUANSOU");
        secretaire = compte(SECRETAIRE, "GBAGUIDI");
    }

    /** Rôle admin pour les deux : la règle du livré tient aux signataires, pas aux droits. */
    private User compte(String email, String nom) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            Role admin = roleRepository.findBySlugAndBranchId("admin", AGENCE).orElseThrow();
            User u = new User();
            u.setBranchId(AGENCE);
            u.setFirstname("Test");
            u.setLastname(nom);
            u.setEmail(email);
            u.setPassword(passwordEncoder.encode("Vq7-tz9Lp2-Xw4"));
            u.setActive(true);
            u.setRoles(List.of(admin));
            return userRepository.save(u);
        });
    }

    /** Patient → demande d'examen validée → compte-rendu signé par {@code signataire}. */
    private Report compteRendu(ReportStatus statut, User signataire) {
        Patient patient = new Patient();
        patient.setBranchId(AGENCE);
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        patient = patientRepository.save(patient);

        TestOrder bon = new TestOrder();
        bon.setBranchId(AGENCE);
        bon.setStatus(TestOrderStatus.VALIDATED);
        bon.setPrelevementDate(LocalDate.now());
        bon.setPatient(patient);
        bon.setCode("EX-" + UUID.randomUUID().toString().substring(0, 8));
        bon = testOrderRepository.save(bon);

        Report r = new Report();
        r.setBranchId(AGENCE);
        r.setTestOrder(bon);
        r.setCode("CO" + bon.getCode());
        r.setStatus(statut);
        r.setSignatureDate(LocalDateTime.now().minusDays(1));
        r.setSignatory1(signataire);
        r.setContent("<p>Texte d'origine</p>");
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

    /** Reprend le compte-rendu tel quel, contenu macroscopique changé. */
    private ReportRequestDto retouche(Report r, String motif) {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setContent("<p>Texte corrigé</p>");
        dto.setContentMicro(r.getContentMicro());
        dto.setStatus(r.getStatus().name());
        dto.setSignatory1Id(r.getSignatory1().getId());
        dto.setReason(motif);
        return dto;
    }

    private ResponseEntity<ApiResponse<ReportResponseDto>> put(Report r, String email, ReportRequestDto dto) {
        return rest.exchange(url("/" + r.getId()), HttpMethod.PUT,
                new HttpEntity<>(dto, enTetes(email)), new ParameterizedTypeReference<>() {});
    }

    // ------------------------------------------------------------------ versions

    @Test
    @DisplayName("Retoucher un compte-rendu validé conserve l'ancien texte, relu par GET /versions/1")
    void compteRenduValide_retouche_conserveLAncienTexte() {
        Report r = compteRendu(ReportStatus.VALIDATED, medecin);

        ResponseEntity<ApiResponse<ReportResponseDto>> reponse = put(r, MEDECIN, retouche(r, null));
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ApiResponse<List<VersionDeCompteRenduDto.Resume>>> liste = rest.exchange(
                url("/" + r.getId() + "/versions"), HttpMethod.GET,
                new HttpEntity<>(enTetes(MEDECIN)), new ParameterizedTypeReference<>() {});
        assertThat(liste.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liste.getBody().data()).hasSize(1);
        VersionDeCompteRenduDto.Resume resume = liste.getBody().data().get(0);
        assertThat(resume.version()).isEqualTo(1);
        assertThat(resume.status()).isEqualTo("VALIDATED");
        assertThat(resume.savedBy()).isEqualTo("AHOUANSOU Test");
        assertThat(resume.savedAt()).isNotNull();

        ResponseEntity<ApiResponse<VersionDeCompteRenduDto>> une = rest.exchange(
                url("/" + r.getId() + "/versions/1"), HttpMethod.GET,
                new HttpEntity<>(enTetes(MEDECIN)), new ParameterizedTypeReference<>() {});
        assertThat(une.getStatusCode()).isEqualTo(HttpStatus.OK);
        VersionDeCompteRenduDto version = une.getBody().data();
        assertThat(version.content()).isEqualTo("<p>Texte d'origine</p>");
        assertThat(version.contentMicro()).isEqualTo("<p>Lames d'origine</p>");
        assertThat(version.signataires()).isEqualTo("AHOUANSOU Test");
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte corrigé</p>");

        // Réenregistrer à l'identique n'empile pas de version.
        ReportRequestDto identique = retouche(r, null);
        assertThat(put(r, MEDECIN, identique).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reportVersionRepository.countByReportId(r.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /versions/{n} inconnu → 404")
    void versionInconnue_404() {
        Report r = compteRendu(ReportStatus.VALIDATED, medecin);
        ResponseEntity<ApiResponse<Object>> reponse = rest.exchange(
                url("/" + r.getId() + "/versions/7"), HttpMethod.GET,
                new HttpEntity<>(enTetes(MEDECIN)), new ParameterizedTypeReference<>() {});
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ POST

    @Test
    @DisplayName("POST /reports avec un reportId → 400, le compte-rendu n'est pas touché")
    void postAvecReportId_400() {
        Report r = compteRendu(ReportStatus.VALIDATED, medecin);
        ReportRequestDto dto = retouche(r, null);
        dto.setReportId(r.getId());

        ResponseEntity<ApiResponse<Object>> reponse = rest.exchange(url(""), HttpMethod.POST,
                new HttpEntity<>(dto, enTetes(MEDECIN)), new ParameterizedTypeReference<>() {});

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reponse.getBody().message()).contains("PUT /reports/{id}");
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte d'origine</p>");
    }

    // ------------------------------------------------------------------ livré

    @Test
    @DisplayName("Compte-rendu livré : sans motif → 422 ; le secrétariat motivé → 200 et motif journalisé")
    void compteRenduLivre_motifExige() {
        Report r = compteRendu(ReportStatus.DELIVERED, medecin);

        ResponseEntity<ApiResponse<ReportResponseDto>> sansMotif = put(r, MEDECIN, retouche(r, "trop court"));
        assertThat(sansMotif.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(sansMotif.getBody().message()).contains("motif");

        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte d'origine</p>");

        // La restriction aux signataires a été levée : le secrétariat, qui
        // saisit et corrige les dossiers au quotidien, se heurtait à un refus
        // sur tout résultat déjà remis — y compris pour une correction de forme
        // qu'il était seul à voir. Le motif, lui, reste exigé.
        ResponseEntity<ApiResponse<ReportResponseDto>> signataire = put(r, SECRETAIRE, retouche(r, MOTIF));
        assertThat(signataire.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                .isEqualTo("<p>Texte corrigé</p>");

        List<LogReport> traces = logReportRepository
                .findByReportIdAndActionOrderByCreatedAtAsc(r.getId(), ReportServiceImpl.ACTION_APRES_SIGNATURE);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getDescription())
                .contains("Motif : " + MOTIF)
                .contains("Contenu macroscopique");
        assertThat(reportVersionRepository.findByReportIdAndVersion(r.getId(), 1))
                .get()
                .satisfies(v -> assertThat(v.getStatus()).isEqualTo(ReportStatus.DELIVERED));
    }

    // ------------------------------------------------------------------ brouillon

    private ResponseEntity<ApiResponse<Void>> brouillon(Report r, String texte) {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setContent(texte);
        dto.setStatus("VALIDATED"); // ignoré : le brouillon ne change jamais l'état
        return rest.exchange(url("/" + r.getId() + "/brouillon"), HttpMethod.PATCH,
                new HttpEntity<>(dto, enTetes(MEDECIN)), new ParameterizedTypeReference<>() {});
    }

    @Test
    @DisplayName("Brouillon d'un compte-rendu en attente : texte enregistré, ni état, ni journal, ni version")
    void brouillon_enAttente_enregistreSansTrace() {
        Report r = compteRendu(ReportStatus.DRAFT, null);
        r.setSignatureDate(null);
        r = reportRepository.save(r);

        assertThat(brouillon(r, "<p>Saisie en cours</p>").getStatusCode()).isEqualTo(HttpStatus.OK);

        Report relu = reportRepository.findById(r.getId()).orElseThrow();
        assertThat(relu.getContent()).isEqualTo("<p>Saisie en cours</p>");
        assertThat(relu.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(relu.getSignatureDate()).isNull();
        assertThat(logReportRepository.findByReportIdOrderByCreatedAtDesc(r.getId())).isEmpty();
        assertThat(reportVersionRepository.countByReportId(r.getId())).isZero();
    }

    @Test
    @DisplayName("Brouillon d'un compte-rendu validé ou signé → 422, texte intact")
    void brouillon_valideOuSigne_refuse() {
        Report valide = compteRendu(ReportStatus.VALIDATED, medecin);
        Report signeEnAttente = compteRendu(ReportStatus.DRAFT, medecin);

        for (Report r : List.of(valide, signeEnAttente)) {
            assertThat(brouillon(r, "<p>Écrasé</p>").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(reportRepository.findById(r.getId()).orElseThrow().getContent())
                    .isEqualTo("<p>Texte d'origine</p>");
        }
    }

    // ------------------------------------------------------------------ ajout seul

    /**
     * Rejoue le bloc de révocation de V107 — le vrai, découpé entre ses
     * marqueurs — sur un rôle {@code appli} qui vient de recevoir tous les
     * droits, puis tente une modification sous ce rôle.
     *
     * <p>Flyway n'est pas joué par les tests, et l'utilisateur du conteneur
     * est superutilisateur : la révocation serait sans effet sur lui. D'où le
     * rôle de test, qui est exactement la situation que la PR demande à
     * l'exploitant de mettre en place.</p>
     */
    @Test
    @DisplayName("V107 : après révocation, un rôle applicatif ne peut plus modifier ni supprimer dans report_versions et log_reports")
    void revocation_interditUpdateEtDelete() throws Exception {
        String migration = new ClassPathResource("db/migration/V107__versions_des_comptes_rendus.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String revocation = migration.substring(
                migration.indexOf("-- >>> revocation"), migration.indexOf("-- <<< revocation"));

        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'appli') "
                + "THEN CREATE ROLE appli; END IF; END $$");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON report_versions, log_reports TO appli");
        jdbc.execute(revocation);

        // Même connexion pour SET ROLE et la tentative : le rôle est un état de session.
        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE appli");
                s.execute("UPDATE report_versions SET content = 'falsifié'");
            } finally {
                try (Statement s = c.createStatement()) {
                    s.execute("RESET ROLE");
                }
            }
            return null;
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");

        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE appli");
                s.execute("DELETE FROM log_reports");
            } finally {
                try (Statement s = c.createStatement()) {
                    s.execute("RESET ROLE");
                }
            }
            return null;
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }
}

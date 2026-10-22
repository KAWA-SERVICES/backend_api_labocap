package com.labo.anapath.testorder;

import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportResponseDto;
import com.labo.anapath.role.PermissionRepository;
import com.labo.anapath.role.Role;
import com.labo.anapath.role.RoleRepository;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un médecin voit toutes les demandes et tous les comptes rendus de son agence,
 * y compris ceux d'un confrère. Le périmètre « ses dossiers seulement » (lot 7,
 * point 4) a été levé le 22/10/2026 : les médecins ne retrouvaient pas par son
 * code une demande qui ne leur était pas attribuée. Seul « Mon espace » reste
 * borné à ses affectations, par construction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class VueDuMedecinIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String MEDECIN = "vue_medecin@labo.bj";
    private static final String CONFRERE = "vue_confrere@labo.bj";

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private PasswordEncoder encodeur;
    @Autowired private Jetons jetons;
    @Autowired private PatientRepository patients;
    @Autowired private TestOrderRepository demandes;
    @Autowired private ReportRepository comptesRendus;

    private User medecin;
    private TestOrder laSienne;
    private TestOrder celleDUnConfrere;
    private Report compteRenduDuConfrere;

    @BeforeEach
    void jeuDeDonnees() {
        Role docteur = roles.findBySlugAndBranchId("docteur", AGENCE).orElseGet(() -> {
            Role r = new Role();
            r.setBranchId(AGENCE);
            r.setName("Docteur");
            r.setSlug("docteur");
            r.setPermissions(List.of(
                    permissions.findBySlug("view-test-orders").orElseThrow(),
                    permissions.findBySlug("view-reports").orElseThrow(),
                    permissions.findBySlug("view-test-order-assignments").orElseThrow()));
            return roles.save(r);
        });
        medecin = users.findByEmail(MEDECIN).orElseGet(() -> creerUtilisateur(MEDECIN, docteur));
        User confrere = users.findByEmail(CONFRERE).orElseGet(() -> creerUtilisateur(CONFRERE, docteur));
        laSienne = demande("Confiée");
        laSienne.setAttribuateDoctorId(medecin.getId());
        laSienne.setAssignedToUserId(medecin.getId());
        laSienne = demandes.save(laSienne);
        celleDUnConfrere = demande("Confrère");
        celleDUnConfrere.setCode("VUE-" + UUID.randomUUID().toString().substring(0, 8));
        celleDUnConfrere.setAttribuateDoctorId(confrere.getId());
        celleDUnConfrere.setAssignedToUserId(confrere.getId());
        celleDUnConfrere = demandes.save(celleDUnConfrere);

        comptesRendus.save(compteRendu(laSienne));
        compteRenduDuConfrere = comptesRendus.save(compteRendu(celleDUnConfrere));
    }

    private User creerUtilisateur(String email, Role role) {
        User u = new User();
        u.setBranchId(AGENCE);
        u.setFirstname("Périmètre");
        u.setLastname("Test");
        u.setEmail(email);
        u.setPassword(encodeur.encode("Vq7-tz9Lp2-Xw4"));
        u.setActive(true);
        u.setRoles(List.of(role));
        return users.save(u);
    }

    private TestOrder demande(String nomDuPatient) {
        Patient p = new Patient();
        p.setBranchId(AGENCE);
        p.setFirstname(nomDuPatient);
        p.setLastname("Périmètre");
        p = patients.save(p);
        TestOrder t = new TestOrder();
        t.setBranchId(AGENCE);
        t.setPatient(p);
        t.setPrelevementDate(LocalDate.now());
        return t;
    }

    private Report compteRendu(TestOrder demande) {
        Report r = new Report();
        r.setBranchId(AGENCE);
        r.setTestOrder(demande);
        return r;
    }

    private HttpEntity<Void> connecte(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(email));
        return new HttpEntity<>(h);
    }

    private <T> ResponseEntity<ApiResponse<PageResponse<T>>> page(String email, String route,
                                                                  ParameterizedTypeReference<ApiResponse<PageResponse<T>>> type) {
        return rest.exchange("http://localhost:" + port + "/api/v1" + route, HttpMethod.GET, connecte(email), type);
    }

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<TestOrderResponseDto>>> DEMANDES =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ReportResponseDto>>> COMPTES_RENDUS =
            new ParameterizedTypeReference<>() {};

    @Test
    @DisplayName("GET /test-orders : le médecin voit aussi la demande d'un confrère")
    void listeDesDemandes() {
        var reponse = page(MEDECIN, "/test-orders?size=100", DEMANDES);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = reponse.getBody().data().content().stream().map(TestOrderResponseDto::id).toList();
        assertThat(ids).contains(laSienne.getId(), celleDUnConfrere.getId());
    }

    @Test
    @DisplayName("GET /test-orders?search=<code> : le médecin retrouve par son code une demande qui ne lui est pas attribuée")
    void rechercheParCode() {
        var reponse = page(MEDECIN, "/test-orders?search=" + celleDUnConfrere.getCode(), DEMANDES);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reponse.getBody().data().content()).extracting(TestOrderResponseDto::id)
                .containsExactly(celleDUnConfrere.getId());
    }

    @Test
    @DisplayName("GET /test-orders/{id} : la fiche d'une demande d'un confrère s'ouvre")
    void ficheDUnConfrere() {
        ResponseEntity<String> fiche = rest.exchange("http://localhost:" + port + "/api/v1/test-orders/" + celleDUnConfrere.getId(),
                HttpMethod.GET, connecte(MEDECIN), String.class);
        assertThat(fiche.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /test-orders/index (mobile) : toutes les demandes de l'agence")
    void indexDuMobile() {
        ResponseEntity<ApiResponse<PageDIndexDto>> reponse = rest.exchange(
                "http://localhost:" + port + "/api/v1/test-orders/index?taille=2000",
                HttpMethod.GET, connecte(MEDECIN), new ParameterizedTypeReference<>() {});
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = reponse.getBody().data().contenu().stream().map(EntreeDIndexDto::id).toList();
        assertThat(ids).contains(laSienne.getId(), celleDUnConfrere.getId());
    }

    @Test
    @DisplayName("GET /reports : le médecin voit aussi le compte rendu d'un confrère")
    void listeDesComptesRendus() {
        var reponse = page(MEDECIN, "/reports?size=100", COMPTES_RENDUS);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> demandesVues = reponse.getBody().data().content().stream()
                .map(ReportResponseDto::testOrderId).toList();
        assertThat(demandesVues).contains(laSienne.getId(), celleDUnConfrere.getId());
    }

    @Test
    @DisplayName("GET /reports/{id} : la fiche du compte rendu d'un confrère s'ouvre")
    void ficheDuCompteRenduDUnConfrere() {
        ResponseEntity<String> fiche = rest.exchange("http://localhost:" + port + "/api/v1/reports/" + compteRenduDuConfrere.getId(),
                HttpMethod.GET, connecte(MEDECIN), String.class);
        assertThat(fiche.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /test-orders/myspace/orders : Mon espace reste borné aux demandes du médecin")
    void monEspaceResteBorne() {
        var reponse = page(MEDECIN, "/test-orders/myspace/orders?size=100", DEMANDES);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = reponse.getBody().data().content().stream().map(TestOrderResponseDto::id).toList();
        assertThat(ids).contains(laSienne.getId()).doesNotContain(celleDUnConfrere.getId());
    }

    /**
     * Les trois autres lectures de comptes rendus portaient le même fragment SQL,
     * retiré par concaténation de blocs de texte : on s'assure qu'elles
     * s'exécutent toujours sur un vrai PostgreSQL. La deuxième page d'une ligne
     * oblige Spring Data à lancer aussi la requête de comptage.
     */
    @Test
    @DisplayName("GET /reports/suivi/list, /search-global, /list : les requêtes et leur comptage s'exécutent")
    void autresLecturesDesComptesRendus() {
        for (String route : List.of("/reports/suivi/list", "/reports/search-global", "/reports/list")) {
            ResponseEntity<String> reponse = rest.exchange("http://localhost:" + port + "/api/v1" + route + "?page=1&size=1",
                    HttpMethod.GET, connecte(MEDECIN), String.class);
            assertThat(reponse.getStatusCode()).as(route).isEqualTo(HttpStatus.OK);
        }
    }
}

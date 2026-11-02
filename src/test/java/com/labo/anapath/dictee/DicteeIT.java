package com.labo.anapath.dictee;

import com.labo.anapath.common.exception.ExternalApiException;
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
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dictée : {@code edit-reports} exigé, quota mensuel par agence vérifié avant
 * l'envoi au fournisseur, consommation comptée sur l'agence de la requête.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class DicteeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        // Une minute par mois : 60 s consommées suffisent à atteindre le quota.
        registry.add("app.dictee.quota-mensuel-minutes", () -> "1");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTRE_AGENCE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String REDACTEUR = "dictee_redacteur@labo.bj";
    private static final String LECTEUR = "dictee_lecteur@labo.bj";

    @MockitoBean private ElevenLabsClient elevenLabs;

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private PasswordEncoder encodeur;
    @Autowired private Jetons jetons;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConsommationDicteeRepository consommations;

    @BeforeEach
    void jeuDeDonnees() {
        reset(elevenLabs);
        consommations.deleteAll();
        jdbc.update("""
                INSERT INTO branches (id, name, code, location, created_at, updated_at)
                VALUES (?, 'Annexe', 'ANNEXE', 'Parakou', NOW(), NOW()) ON CONFLICT (id) DO NOTHING
                """, AUTRE_AGENCE);
        if (users.findByEmail(REDACTEUR).isEmpty()) {
            creerUtilisateur(REDACTEUR, role("edit-reports"));
        }
        if (users.findByEmail(LECTEUR).isEmpty()) {
            creerUtilisateur(LECTEUR, role("view-reports"));
        }
        when(elevenLabs.transcrire(any(), anyString()))
                .thenReturn(new ElevenLabsClient.Transcription(" Carcinome canalaire infiltrant. ", 4.2));
    }

    private Role role(String permission) {
        Role r = new Role();
        r.setBranchId(AGENCE);
        r.setName("Rôle " + permission);
        r.setSlug("dictee-" + UUID.randomUUID().toString().substring(0, 8));
        r.setPermissions(List.of(permissions.findBySlug(permission).orElseThrow()));
        return roles.save(r);
    }

    private void creerUtilisateur(String email, Role role) {
        User u = new User();
        u.setBranchId(AGENCE);
        u.setFirstname("Dictée");
        u.setLastname("Test");
        u.setEmail(email);
        u.setPassword(encodeur.encode("Vq7-tz9Lp2-Xw4"));
        u.setActive(true);
        u.setRoles(List.of(role));
        users.save(u);
    }

    private ResponseEntity<String> envoyer(String email, String typeAudio) {
        HttpHeaders partie = new HttpHeaders();
        partie.setContentType(MediaType.parseMediaType(typeAudio));
        MultiValueMap<String, Object> corps = new LinkedMultiValueMap<>();
        corps.add("audio", new HttpEntity<>(new ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return "segment.webm";
            }
        }, partie));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(email));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("http://localhost:" + port + "/api/v1/dictees", new HttpEntity<>(corps, h), String.class);
    }

    private void consommer(UUID agence, int secondes, String quand) {
        jdbc.update("INSERT INTO consommation_dictee (id, branch_id, secondes, created_at) VALUES (?, ?, ?, " + quand + ")",
                UUID.randomUUID(), agence, secondes);
    }

    @Test
    @DisplayName("edit-reports : texte rendu, durée comptée sur l'agence et l'auteur")
    void transcrit() {
        ResponseEntity<String> r = envoyer(REDACTEUR, "audio/webm;codecs=opus");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"texte\":\"Carcinome canalaire infiltrant.\"");
        List<ConsommationDictee> lignes = consommations.findAll();
        assertThat(lignes).hasSize(1);
        assertThat(lignes.getFirst().getBranchId()).isEqualTo(AGENCE);
        assertThat(lignes.getFirst().getUserId()).isEqualTo(users.findByEmail(REDACTEUR).orElseThrow().getId());
        assertThat(lignes.getFirst().getSecondes()).isEqualByComparingTo(new BigDecimal("4.20"));
    }

    @Test
    @DisplayName("sans edit-reports → 403, rien n'est envoyé au fournisseur")
    void lecteur403() {
        assertThat(envoyer(LECTEUR, "audio/webm").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(elevenLabs, never()).transcrire(any(), anyString());
    }

    @Test
    @DisplayName("quota du mois atteint → 422 DICTEE_QUOTA_ATTEINT, rien n'est envoyé ni compté")
    void quotaAtteint() {
        consommer(AGENCE, 60, "NOW()");

        ResponseEntity<String> r = envoyer(REDACTEUR, "audio/webm");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody()).contains("DICTEE_QUOTA_ATTEINT");
        verify(elevenLabs, never()).transcrire(any(), anyString());
        assertThat(consommations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("la consommation d'une autre agence ou du mois dernier ne compte pas")
    void quotaParAgenceEtParMois() {
        consommer(AUTRE_AGENCE, 600, "NOW()");
        consommer(AGENCE, 600, "date_trunc('month', NOW()) - INTERVAL '1 day'");

        assertThat(envoyer(REDACTEUR, "audio/webm").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("ElevenLabs indisponible → 503, la réservation est rendue (rien n'est compté)")
    void echecRendLaReservation() {
        when(elevenLabs.transcrire(any(), anyString()))
                .thenThrow(new ExternalApiException("Service de transcription indisponible"));

        assertThat(envoyer(REDACTEUR, "audio/webm").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(consommations.count()).isZero();
    }

    @Test
    @DisplayName("trois segments simultanés pour une minute de quota : un seul passe, le quota tient")
    void quotaTientEnParallele() throws Exception {
        when(elevenLabs.transcrire(any(), anyString())).thenAnswer(i -> {
            Thread.sleep(500);
            return new ElevenLabsClient.Transcription("ok", 60.0);
        });
        // Jeton émis avant : Jetons écrit branch_user, pas de concurrence là-dessus.
        jetons.pour(REDACTEUR);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<HttpStatusCode>> envois = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            envois.add(pool.submit(() -> envoyer(REDACTEUR, "audio/webm").getStatusCode()));
        }
        List<HttpStatusCode> statuts = new ArrayList<>();
        for (Future<HttpStatusCode> f : envois) {
            statuts.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuts).filteredOn(HttpStatus.OK::equals).hasSize(1);
        assertThat(statuts).filteredOn(HttpStatus.UNPROCESSABLE_CONTENT::equals).hasSize(2);
        verify(elevenLabs, times(1)).transcrire(any(), anyString());
    }

    @Test
    @DisplayName("fichier qui n'est pas de l'audio → 422, rien n'est envoyé")
    void pasDeLAudio() {
        assertThat(envoyer(REDACTEUR, "application/pdf").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(elevenLabs, never()).transcrire(any(), anyString());
    }
}

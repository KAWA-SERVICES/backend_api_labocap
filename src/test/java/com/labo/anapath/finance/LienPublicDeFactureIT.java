package com.labo.anapath.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le lien du SMS s'ouvre sans compte, à son adresse courte comme à l'ancienne :
 * un jeton inconnu y reçoit la page « Lien introuvable », jamais un 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class LienPublicDeFactureIT {

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

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    @Test
    @DisplayName("GET /f/{jeton} et /api/v1/public/invoices/{jeton} sans compte → page « Lien introuvable »")
    void lienSansCompte_atteintLaRoute() {
        for (String chemin : new String[]{"/f/inconnu123", "/api/v1/public/invoices/inconnu123"}) {
            ResponseEntity<String> reponse = rest.getForEntity("http://localhost:" + port + chemin, String.class);
            assertThat(reponse.getStatusCode()).as(chemin).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(reponse.getBody()).as(chemin).contains("Lien introuvable");
        }
    }
}

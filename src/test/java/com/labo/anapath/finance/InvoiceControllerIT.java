package com.labo.anapath.finance;

import com.labo.anapath.testsupport.Jetons;
import com.labo.anapath.auth.LoginRequest;
import com.labo.anapath.auth.LoginResponse;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.role.Role;
import com.labo.anapath.role.RoleRepository;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class InvoiceControllerIT {

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

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private CashboxRepository cashboxRepository;

    @LocalServerPort private int port;

    @Autowired private Jetons jetons;

    private static final UUID SEED_BRANCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ADMIN_EMAIL    = "admin_invoice_it@labo.bj";
    private static final String ADMIN_PASSWORD = "adminPass123";

    private UUID invoiceId;
    private UUID cashboxVenteId;

    @BeforeEach
    void setup() {
        // Clean up from previous test runs (cashboxes and invoices without patient/testOrder)
        invoiceRepository.deleteAll(
                invoiceRepository.findAll().stream()
                        .filter(i -> i.getPatient() == null && i.getTestOrder() == null)
                        .toList());
        cashboxRepository.deleteAll(
                cashboxRepository.findAll().stream()
                        .filter(c -> "vente".equals(c.getType()) && SEED_BRANCH_ID.equals(c.getBranchId()))
                        .toList());

        if (userRepository.findByEmail(ADMIN_EMAIL).isEmpty()) {
            Role adminRole = roleRepository.findBySlugAndBranchId("admin", SEED_BRANCH_ID)
                    .orElseThrow(() -> new IllegalStateException("ADMIN role not seeded"));
            User admin = new User();
            admin.setBranchId(SEED_BRANCH_ID);
            admin.setFirstname("Admin");
            admin.setLastname("InvoiceTest");
            admin.setEmail(ADMIN_EMAIL);
            admin.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
            admin.setActive(true);
            admin.setRoles(List.of(adminRole));
            userRepository.save(admin);
        }

        // Create caisse vente for branch
        Cashbox cashVente = new Cashbox();
        cashVente.setBranchId(SEED_BRANCH_ID);
        cashVente.setName("Caisse Vente IT");
        cashVente.setType("vente");
        cashVente.setBalance(new BigDecimal("100000.00"));
        cashboxVenteId = cashboxRepository.save(cashVente).getId();

        // Create a pending invoice directly (no patient/testOrder needed — nullable)
        Invoice inv = new Invoice();
        inv.setBranchId(SEED_BRANCH_ID);
        inv.setTotal(new BigDecimal("5000.00"));
        inv.setPaid(false);
        inv.setStatus(InvoiceStatus.PENDING);
        inv.setStatusInvoice(0);
        invoiceId = invoiceRepository.save(inv).getId();
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/invoices";
    }

    private String loginAndGetToken() {
        // Jeton émis directement : la connexion HTTP exige un code à usage
        // unique et n'est pas ce que ce test éprouve (voir Jetons).
        return jetons.pour(ADMIN_EMAIL);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    @DisplayName("GET /invoices → 200 avec liste paginée")
    void findAll_returns200() {
        String token = loginAndGetToken();

        ResponseEntity<ApiResponse<Map<String, Object>>> response = restTemplate.exchange(
                baseUrl(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).containsKey("content");
    }

    @Test
    @DisplayName("PATCH /invoices/{id}/status → 200, invoice paid=true, solde caisse crédité")
    void markAsPaid_returns200AndCreditsCashbox() {
        String token = loginAndGetToken();

        Map<String, Object> body = Map.of("payment", "ESPECES");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = restTemplate.exchange(
                baseUrl() + "/" + invoiceId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().get("paid")).isEqualTo(true);

        // Verify cashbox balance increased
        Cashbox updated = cashboxRepository.findById(cashboxVenteId).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("105000.00"));
    }

    @Test
    @DisplayName("PATCH /invoices/{id}/status → 422 INVOICE_ALREADY_PAID sur doublon")
    void markAsPaid_alreadyPaid_returns422() {
        String token = loginAndGetToken();

        // Pay once
        Map<String, Object> body = Map.of("payment", "ESPECES");
        restTemplate.exchange(
                baseUrl() + "/" + invoiceId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        // Pay again → 422
        ResponseEntity<ApiResponse<Map<String, Object>>> response = restTemplate.exchange(
                baseUrl() + "/" + invoiceId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    @DisplayName("POST /invoices/{id}/sms sur une facture non normalisée → 422, rien n'est envoyé")
    void envoyerSms_factureNonNormalisee_returns422() {
        String token = loginAndGetToken();

        ResponseEntity<ApiResponse<Map<String, Object>>> response = restTemplate.exchange(
                baseUrl() + "/" + invoiceId + "/sms", HttpMethod.POST,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().message()).contains("Normalisez");
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getShareSmsSentAt()).isNull();
    }

    @Test
    @DisplayName("GET /invoices/{id} → expose shareSmsSentAt, nul tant qu'aucun SMS n'est parti")
    void findById_exposeLaDateDEnvoiDuSms() {
        String token = loginAndGetToken();

        ResponseEntity<ApiResponse<Map<String, Object>>> response = restTemplate.exchange(
                baseUrl() + "/" + invoiceId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).containsKey("shareSmsSentAt");
        assertThat(response.getBody().data().get("shareSmsSentAt")).isNull();
    }
}

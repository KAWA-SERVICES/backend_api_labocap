package com.labo.anapath.dashboard;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Le tableau de bord exige {@code view-dashboard}, les montants {@code view-dashboard-finance} en plus. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class DashboardAccesIT {

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

    static final List<String> ROUTES = List.of(
            "/stats", "/secretariat-stats", "/reports-today", "/doctor-stats", "/top-examens",
            "/monthly-stats", "/connected-users", "/revenue", "/invoice-status",
            "/doctor/exam-status", "/doctor/appointments", "/doctor/orders", "/doctor/orders-today", "/rappels");
    static final List<String> MONTANTS = List.of("/revenue", "/invoice-status");

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SANS_DASHBOARD = "dashboard_sans@labo.bj";
    private static final String SANS_FINANCE = "dashboard_sans_finance@labo.bj";
    private static final String ADMIN = "dashboard_admin@labo.bj";

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private PasswordEncoder encodeur;
    @Autowired private Jetons jetons;

    @BeforeEach
    void jeuDeDonnees() {
        if (users.findByEmail(SANS_DASHBOARD).isEmpty()) {
            creerUtilisateur(SANS_DASHBOARD, role("view-reports"));
        }
        if (users.findByEmail(SANS_FINANCE).isEmpty()) {
            creerUtilisateur(SANS_FINANCE, role("view-dashboard"));
        }
        if (users.findByEmail(ADMIN).isEmpty()) {
            creerUtilisateur(ADMIN, roles.findBySlugAndBranchId("admin", AGENCE).orElseThrow());
        }
    }

    private Role role(String permission) {
        Role r = new Role();
        r.setBranchId(AGENCE);
        r.setName("Rôle " + permission);
        r.setSlug("dashboard-" + UUID.randomUUID().toString().substring(0, 8));
        r.setPermissions(List.of(permissions.findBySlug(permission).orElseThrow()));
        return roles.save(r);
    }

    private void creerUtilisateur(String email, Role role) {
        User u = new User();
        u.setBranchId(AGENCE);
        u.setFirstname("Tableau");
        u.setLastname("De bord");
        u.setEmail(email);
        u.setPassword(encodeur.encode("Vq7-tz9Lp2-Xw4"));
        u.setActive(true);
        u.setRoles(List.of(role));
        users.save(u);
    }

    private HttpStatus statut(String email, String route) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(email));
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + "/api/v1/dashboard" + route,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        return HttpStatus.valueOf(r.getStatusCode().value());
    }

    @Test
    @DisplayName("sans view-dashboard → 403 sur les treize routes")
    void sansViewDashboard_403Partout() {
        for (String route : ROUTES) {
            assertThat(statut(SANS_DASHBOARD, route)).as(route).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("view-dashboard seul : les compteurs oui, les montants non")
    void sansFinance_403SurLesMontants() {
        for (String route : ROUTES) {
            HttpStatus attendu = MONTANTS.contains(route) ? HttpStatus.FORBIDDEN : HttpStatus.OK;
            assertThat(statut(SANS_FINANCE, route)).as(route).isEqualTo(attendu);
        }
    }

    @Test
    @DisplayName("admin (toutes les permissions) → 200 partout")
    void admin_200Partout() {
        for (String route : ROUTES) {
            assertThat(statut(ADMIN, route)).as(route).isEqualTo(HttpStatus.OK);
        }
    }
}

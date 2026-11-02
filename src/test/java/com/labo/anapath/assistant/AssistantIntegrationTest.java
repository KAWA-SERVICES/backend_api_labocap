package com.labo.anapath.assistant;

import com.labo.anapath.assistant.llm.ClientAnthropic;
import com.labo.anapath.assistant.llm.FournisseurLlm;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.branch.BranchContext;
import com.labo.anapath.common.branch.BranchContextFilter;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.role.Role;
import com.labo.anapath.role.RoleRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignmentService;
import com.labo.anapath.testorder.TestOrderRepository;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * L'assistant de bout en bout, avec un fournisseur factice qui joue un script
 * et garde chaque requête qu'il reçoit — ce qui « part chez le fournisseur ».
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureTestRestTemplate
class AssistantIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_labo").withUsername("test").withPassword("test");

    static final String CLE_SECRETS = Base64.getEncoder().encodeToString(new byte[32]);

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.secrets.key", () -> CLE_SECRETS);
        r.add("app.assistant.messages-par-minute", () -> "1000");
    }

    private static final UUID AGENCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTRE_AGENCE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String ADMIN = "admin_assistant@labo.bj";
    private static final String DOCTEUR = "docteur_assistant@labo.bj";
    private static final String CLE_API = "sk-ant-cle-de-test-123456";

    @MockitoBean ClientAnthropic fournisseur;

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Jetons jetons;
    @Autowired UserRepository utilisateurs;
    @Autowired RoleRepository roles;
    @Autowired PatientRepository patients;
    @Autowired TestOrderRepository demandes;
    @Autowired TestOrderAssignmentService affectations;
    @Autowired PasswordEncoder motsDePasse;
    @LocalServerPort int port;

    /** Le script du fournisseur : une réponse par appel, et les requêtes reçues. */
    private final Deque<Function<FournisseurLlm.Requete, List<FournisseurLlm.Evenement>>> script = new ArrayDeque<>();
    private final List<FournisseurLlm.Requete> recues = new ArrayList<>();

    private UUID docteurId;
    private Patient patient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparer() {
        script.clear();
        recues.clear();
        when(fournisseur.code()).thenReturn("ANTHROPIC");
        doAnswer(inv -> {
            FournisseurLlm.Requete r = inv.getArgument(0);
            recues.add(r);
            Consumer<FournisseurLlm.Evenement> sortie = inv.getArgument(1);
            script.removeFirst().apply(r).forEach(sortie);
            return null;
        }).when(fournisseur).completer(any(), any());

        jdbc.update("""
                INSERT INTO branches (id, name, code, location, created_at, updated_at)
                SELECT ?, 'Annexe', 'ANNEXE', 'Parakou', NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM branches WHERE id = ?)""", AUTRE_AGENCE, AUTRE_AGENCE);

        if (utilisateurs.findByEmail(ADMIN).isEmpty()) {
            Role admin = roles.findBySlugAndBranchId("admin", AGENCE).orElseThrow();
            utilisateurs.save(utilisateur(ADMIN, "Admin", "Assistant", admin));
        }
        if (utilisateurs.findByEmail(DOCTEUR).isEmpty()) {
            Role docteur = roles.findBySlugAndBranchId("docteur", AGENCE).orElseGet(() -> {
                Role r = new Role();
                r.setBranchId(AGENCE);
                r.setName("Docteur");
                r.setSlug("docteur");
                return roles.save(r);
            });
            // Un médecin : sa file, les demandes, les patients, les comptes rendus — pas les factures.
            for (String p : List.of("view-assistant", "view-test-order-assignments", "view-test-orders",
                    "view-patients", "view-reports")) {
                jdbc.update("""
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT ?, id FROM permissions WHERE slug = ?
                        AND NOT EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions x ON x.id = rp.permission_id
                                        WHERE rp.role_id = ? AND x.slug = ?)""", docteur.getId(), p, docteur.getId(), p);
            }
            utilisateurs.save(utilisateur(DOCTEUR, "Afiavi", "Houngbo", docteur));
        }
        docteurId = utilisateurs.findByEmail(DOCTEUR).orElseThrow().getId();
        jdbc.update("""
                INSERT INTO branch_user (user_id, branch_id, is_default)
                SELECT ?, ?, TRUE WHERE NOT EXISTS (SELECT 1 FROM branch_user WHERE user_id = ? AND branch_id = ?)""",
                utilisateurs.findByEmail(ADMIN).orElseThrow().getId(), AUTRE_AGENCE,
                utilisateurs.findByEmail(ADMIN).orElseThrow().getId(), AUTRE_AGENCE);

        patient = patients.findAll().stream().filter(p -> "PAI-001".equals(p.getCode())).findFirst().orElseGet(() -> {
            Patient p = new Patient();
            p.setBranchId(AGENCE);
            p.setCode("PAI-001");
            p.setFirstname("Koffi");
            p.setLastname("Agossou");
            p.setTelephone1("0197112233");
            return patients.save(p);
        });

        reglages(null);
    }

    private User utilisateur(String email, String prenom, String nom, Role role) {
        User u = new User();
        u.setBranchId(AGENCE);
        u.setFirstname(prenom);
        u.setLastname(nom);
        u.setEmail(email);
        u.setPassword(motsDePasse.encode("motDePasse123"));
        u.setActive(true);
        u.setRoles(List.of(role));
        return u;
    }

    private void reglages(Long quota) {
        var corps = new java.util.HashMap<String, Object>(Map.of("actif", true, "fournisseur", "ANTHROPIC",
                "modele", "claude-opus-5-5", "caviardage", true, "cleApi", CLE_API));
        corps.put("quotaMensuelJetons", quota);
        ResponseEntity<String> r = http.exchange(url("/reglages"), HttpMethod.PUT,
                new HttpEntity<>(corps, entetes(ADMIN, AGENCE)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TestOrder demande(String code, boolean urgent) {
        return demandes.findByCodeAndBranchId(code, AGENCE).orElseGet(() -> {
            TestOrder o = new TestOrder();
            o.setBranchId(AGENCE);
            o.setCode(code);
            o.setPatient(patient);
            o.setPrelevementDate(LocalDate.now());
            o.setIsUrgent(urgent);
            return demandes.save(o);
        });
    }

    // -------------------------------------------------------------------------
    // Outillage HTTP / SSE / script
    // -------------------------------------------------------------------------

    private String url(String chemin) {
        return "http://localhost:" + port + "/api/v1/assistant" + chemin;
    }

    private HttpHeaders entetes(String email, UUID agence) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jetons.pour(email));
        if (agence != null) h.set(BranchContextFilter.BRANCH_HEADER, agence.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID conversation(String email) {
        ResponseEntity<String> r = http.exchange(url("/conversations"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), entetes(email, AGENCE)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json.readTree(r.getBody()).path("data").path("id").asString());
    }

    private record Evenement(String nom, JsonNode donnees) {}

    private ResponseEntity<String> poster(String email, String chemin, Object corps) {
        return http.exchange(url(chemin), HttpMethod.POST, new HttpEntity<>(corps, entetes(email, AGENCE)), String.class);
    }

    private List<Evenement> flux(ResponseEntity<String> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        List<Evenement> liste = new ArrayList<>();
        for (String bloc : r.getBody().split("\n\n")) {
            String nom = null, donnees = null;
            for (String ligne : bloc.split("\n")) {
                if (ligne.startsWith("event: ")) nom = ligne.substring(7);
                if (ligne.startsWith("data: ")) donnees = ligne.substring(6);
            }
            if (nom != null) liste.add(new Evenement(nom, json.readTree(donnees)));
        }
        return liste;
    }

    private List<Evenement> envoyer(String email, UUID conv, String texte) {
        return flux(poster(email, "/conversations/" + conv + "/messages", Map.of("texte", texte)));
    }

    private static String texteAffiche(List<Evenement> evs) {
        StringBuilder b = new StringBuilder();
        evs.stream().filter(e -> e.nom().equals("token")).forEach(e -> b.append(e.donnees().path("texte").asString()));
        return b.toString();
    }

    private static Evenement premier(List<Evenement> evs, String nom) {
        return evs.stream().filter(e -> e.nom().equals(nom)).findFirst()
                .orElseThrow(() -> new AssertionError("Pas d'événement " + nom + " dans " + evs));
    }

    private static List<FournisseurLlm.Evenement> repondre(String texte) {
        return List.of(new FournisseurLlm.TexteDelta(texte),
                new FournisseurLlm.BlocTermine(JsonNodeFactory.instance.objectNode().put("type", "text").put("text", texte)),
                new FournisseurLlm.Consommation(100, 20), new FournisseurLlm.Fin("end_turn"));
    }

    private List<FournisseurLlm.Evenement> appeler(String id, String outil, String argsJson) {
        var bloc = JsonNodeFactory.instance.objectNode().put("type", "tool_use").put("id", id).put("name", outil);
        bloc.set("input", json.readTree(argsJson));
        return List.of(new FournisseurLlm.BlocTermine(bloc), new FournisseurLlm.Consommation(100, 20),
                new FournisseurLlm.Fin("tool_use"));
    }

    private String envoye(FournisseurLlm.Requete r) {
        return r.messages().toString() + r.systeme();
    }

    private static String jeton(String texte, String genre) {
        Matcher m = Pattern.compile(genre + "_[0-9a-f]{16,}").matcher(texte);
        if (!m.find()) throw new AssertionError("Pas de jeton " + genre + " dans : " + texte);
        return m.group();
    }

    // -------------------------------------------------------------------------
    // Les tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Masquage : aucun nom ni téléphone de patient ne part ; l'écran affiche le nom rétabli ; la lecture est journalisée")
    void masquageEtJournal() throws Exception {
        UUID conv = conversation(ADMIN);
        script.add(r -> appeler("t1", "patient_chercher", "{\"recherche\":\"PAI-001\"}"));
        script.add(r -> repondre("J'ai trouvé " + jeton(envoye(r), "NOM") + " dans l'agence."));

        List<Evenement> evs = envoyer(ADMIN, conv, "Trouve le patient PAI-001");

        assertThat(premier(evs, "tool_call").donnees().path("libelle").asString()).isEqualTo("Patients consultés");
        assertThat(premier(evs, "tool_result").donnees().path("donnees").toString()).contains("Agossou");
        assertThat(texteAffiche(evs)).contains("Koffi").doesNotContain("NOM_");
        String deuxieme = envoye(recues.get(1));
        assertThat(deuxieme).doesNotContain("Koffi", "Agossou", "0197112233", patient.getId().toString());

        // Deuxième message : l'historique déjà envoyé repart à l'identique, et la
        // lecture du dossier est inscrite au journal des accès.
        script.add(r -> appeler("t2", "patient_historique", "{\"patientId\":\"" + jeton(envoye(r), "PATIENT") + "\"}"));
        script.add(r -> repondre("C'est noté."));
        envoyer(ADMIN, conv, "Est-il déjà venu chez nous ?");

        assertThat(envoye(recues.get(2))).startsWith(recues.get(1).messages().toString().replaceAll("]$", ""));
        assertThat(envoye(recues.get(3))).doesNotContain("Agossou");
        long lignes = 0;
        for (int i = 0; i < 50 && lignes == 0; i++) {
            lignes = jdbc.queryForObject("SELECT COUNT(*) FROM journal_acces WHERE entity_id = ? AND entity_type = 'PATIENT'",
                    Long.class, patient.getId().toString());
            if (lignes == 0) Thread.sleep(100);
        }
        assertThat(lignes).isPositive();
    }

    @Test
    @DisplayName("Droits : un médecin sans accès aux factures ne reçoit pas ces outils, et un appel forcé est refusé et tracé")
    void pariteDesDroits() {
        UUID conv = conversation(DOCTEUR);
        script.add(r -> appeler("t1", "recette_du_jour", "{}"));
        script.add(r -> repondre("Vous n'avez pas accès à la caisse."));

        List<Evenement> evs = envoyer(DOCTEUR, conv, "Combien a encaissé la caisse aujourd'hui ?");

        List<String> outils = new ArrayList<>();
        recues.getFirst().outils().forEach(o -> outils.add(o.path("name").asString()));
        assertThat(outils).contains("file_priorite", "patient_historique", "compte_rendu_ouvrir")
                .doesNotContain("recette_du_jour", "factures_impayees", "demande_affecter_au_medecin");
        assertThat(premier(evs, "tool_result").donnees().path("ok").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_appels_outils WHERE outil = 'recette_du_jour' "
                + "AND statut = 'REFUSE' AND conversation_id = ?", Long.class, conv)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Écriture : rien n'est fait avant confirmation ; confirmée une seule fois ; refus et message intercalé n'écrivent rien")
    void confirmation() {
        TestOrder o = demande("AI-26-0001", false);
        String affecter = "{\"codeDemande\":\"AI-26-0001\",\"medecinId\":\"" + docteurId + "\"}";
        String compte = "SELECT COUNT(*) FROM test_order_assignment_details d JOIN test_order_assignments a "
                + "ON a.id = d.test_order_assignment_id WHERE d.test_order_id = ? AND a.user_id = ?";

        UUID conv = conversation(ADMIN);
        script.add(r -> appeler("w1", "demande_affecter_au_medecin", affecter));
        List<Evenement> evs = envoyer(ADMIN, conv, "Affecte AI-26-0001 au Dr Houngbo");
        JsonNode carte = premier(evs, "confirmation_required").donnees();
        assertThat(carte.toString()).contains("AI-26-0001", "Dr Afiavi Houngbo");
        assertThat(jdbc.queryForObject(compte, Long.class, o.getId(), docteurId)).isZero();

        script.add(r -> repondre("C'est fait."));
        List<Evenement> suite = flux(poster(ADMIN, "/conversations/" + conv + "/confirmations/w1", Map.of("accepter", true)));
        assertThat(premier(suite, "tool_result").donnees().path("ok").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(compte, Long.class, o.getId(), docteurId)).isEqualTo(1L);
        assertThat(poster(ADMIN, "/conversations/" + conv + "/confirmations/w1", Map.of("accepter", true)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // Refus.
        TestOrder autre = demande("AI-26-0002", false);
        String affecterAutre = affecter.replace("AI-26-0001", "AI-26-0002");
        UUID conv2 = conversation(ADMIN);
        script.add(r -> appeler("w2", "demande_affecter_au_medecin", affecterAutre));
        envoyer(ADMIN, conv2, "Affecte AI-26-0002");
        script.add(r -> repondre("D'accord, je n'y touche pas."));
        flux(poster(ADMIN, "/conversations/" + conv2 + "/confirmations/w2", Map.of("accepter", false)));
        assertThat(jdbc.queryForObject(compte, Long.class, autre.getId(), docteurId)).isZero();

        // Message intercalé : l'appel en attente reçoit une réponse « non exécuté ».
        UUID conv3 = conversation(ADMIN);
        script.add(r -> appeler("w3", "demande_affecter_au_medecin", affecterAutre));
        envoyer(ADMIN, conv3, "Affecte AI-26-0002");
        script.add(r -> repondre("Bien."));
        envoyer(ADMIN, conv3, "Non, laisse tomber.");
        assertThat(envoye(recues.getLast())).contains("Non exécuté");
        assertThat(jdbc.queryForObject(compte, Long.class, autre.getId(), docteurId)).isZero();
        assertThat(poster(ADMIN, "/conversations/" + conv3 + "/confirmations/w3", Map.of("accepter", true)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Cloisonnement : la conversation d'un autre, ou d'une autre agence, est introuvable")
    void cloisonnement() {
        UUID conv = conversation(ADMIN);
        assertThat(http.exchange(url("/conversations/" + conv + "/messages"), HttpMethod.GET,
                new HttpEntity<>(entetes(DOCTEUR, AGENCE)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange(url("/conversations/" + conv + "/messages"), HttpMethod.GET,
                new HttpEntity<>(entetes(ADMIN, AUTRE_AGENCE)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Quota atteint : refus avant tout appel au fournisseur")
    void quota() {
        UUID conv = conversation(ADMIN);
        script.add(r -> repondre("Bonjour."));
        envoyer(ADMIN, conv, "Bonjour");
        try {
            reglages(1L);
            int avant = recues.size();
            ResponseEntity<String> r = poster(ADMIN, "/conversations/" + conv + "/messages", Map.of("texte", "Encore"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(recues).hasSize(avant);
        } finally {
            reglages(null);
        }
    }

    @Test
    @DisplayName("Clé API : chiffrée en base, jamais renvoyée")
    void cleApi() {
        String brut = jdbc.queryForObject("SELECT cle_api FROM assistant_reglages WHERE id = 1", String.class);
        assertThat(brut).isNotBlank().doesNotContain(CLE_API);
        ResponseEntity<String> r = http.exchange(url("/reglages"), HttpMethod.GET,
                new HttpEntity<>(entetes(ADMIN, AGENCE)), String.class);
        assertThat(r.getBody()).doesNotContain(CLE_API).contains("\"cleApiConfiguree\":true");
        assertThat(http.exchange(url("/reglages"), HttpMethod.GET, new HttpEntity<>(entetes(DOCTEUR, AGENCE)),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Priorité de la file : urgents, puis en retard, puis par lot")
    void prioriteDeLaFile() {
        TestOrder normale = demande("AI-26-0101", false);
        TestOrder enRetard = demande("AI-26-0102", false);
        TestOrder urgente = demande("AI-26-0103", true);
        BranchContext.set(AGENCE);
        try {
            for (TestOrder o : List.of(normale, enRetard, urgente)) {
                affectations.affecterAuMedecin(o.getId(), docteurId, true, AGENCE);
            }
        } finally {
            BranchContext.clear();
        }
        jdbc.update("UPDATE test_orders SET created_at = NOW() - INTERVAL '40 days' WHERE id = ?", enRetard.getId());

        List<String> codes = affectations.prioritesDeLaFile(docteurId, 20, Discipline.PATHOLOGY).stream()
                .map(d -> d.code()).filter(c -> c.startsWith("AI-26-01")).toList();
        assertThat(codes).containsExactly("AI-26-0103", "AI-26-0102", "AI-26-0101");
    }

    @Test
    @DisplayName("Panne du fournisseur au milieu du tour : événement error, et le tour suivant passe")
    void panne() {
        UUID conv = conversation(ADMIN);
        script.add(r -> { throw new FournisseurLlm.Indisponible("Anthropic limite les appels pour le moment.", null); });
        List<Evenement> evs = envoyer(ADMIN, conv, "Ma file ?");
        assertThat(premier(evs, "error").donnees().path("code").asString()).isEqualTo("fournisseur_indisponible");

        script.add(r -> repondre("Me revoilà."));
        assertThat(texteAffiche(envoyer(ADMIN, conv, "Et maintenant ?"))).isEqualTo("Me revoilà.");
    }

    @Test
    @DisplayName("Recherche dans les CR : outil proposé au médecin, exécuté sur Postgres, mot cherché non conservé dans la trace")
    void rechercheDansLesComptesRendus() {
        UUID conv = conversation(DOCTEUR);
        script.add(r -> appeler("t1", "compte_rendu_rechercher", "{\"recherche\":\"adénocarcinome\",\"depuis\":\"2026-01-01\"}"));
        script.add(r -> repondre("Aucun compte rendu ne contient ce mot."));

        List<Evenement> evs = envoyer(DOCTEUR, conv, "Les CR qui parlent d'adénocarcinome depuis janvier ?");

        List<String> outils = new ArrayList<>();
        recues.getFirst().outils().forEach(o -> outils.add(o.path("name").asString()));
        assertThat(outils).contains("compte_rendu_rechercher", "signaler_besoin");
        JsonNode resultat = premier(evs, "tool_result").donnees();
        assertThat(resultat.path("ok").asBoolean()).as(resultat.toString()).isTrue();
        assertThat(resultat.path("donnees").has("total")).isTrue();
        assertThat(jdbc.queryForObject("SELECT arguments::text FROM assistant_appels_outils WHERE conversation_id = ?",
                String.class, conv)).doesNotContain("adénocarcinome").contains("non conservé");
    }

    @Test
    @DisplayName("Boucle d'amélioration : besoin signalé masqué, avis 👎 masqué, rapport admin sans nom, réservé aux admins")
    void boucleDAmelioration() {
        UUID conv = conversation(DOCTEUR);
        script.add(r -> appeler("t1", "patient_chercher", "{\"recherche\":\"PAI-001\"}"));
        script.add(r -> repondre("Trouvé."));
        envoyer(DOCTEUR, conv, "Trouve PAI-001");

        // Le modèle signale un besoin en reprenant un jeton : il doit rester masqué en base.
        script.add(r -> appeler("t2", "signaler_besoin", "{\"demande\":\"Délai moyen de rendu pour "
                + jeton(envoye(r), "NOM") + "\",\"raison\":\"outil_manquant\"}"));
        script.add(r -> repondre("Je ne sais pas encore le faire ; c'est noté pour l'équipe."));
        List<Evenement> evs = envoyer(DOCTEUR, conv, "Quel est mon délai moyen de rendu ?");
        int seq = premier(evs, "done").donnees().path("seq").asInt();
        assertThat(seq).isPositive();
        String besoin = jdbc.queryForObject("SELECT demande FROM assistant_besoins WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, docteurId);
        assertThat(besoin).contains("NOM_").doesNotContain("Agossou", "Koffi");

        ResponseEntity<String> avis = http.exchange(url("/conversations/" + conv + "/messages/" + seq + "/avis"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("avis", -1, "commentaire", "Inutile pour Agossou"), entetes(DOCTEUR, AGENCE)), String.class);
        assertThat(avis.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT avis_commentaire FROM assistant_messages WHERE conversation_id = ? AND seq = ?",
                String.class, conv, seq)).contains("NOM_").doesNotContain("Agossou");
        // Un autre utilisateur ne note pas la conversation d'autrui.
        assertThat(http.exchange(url("/conversations/" + conv + "/messages/" + seq + "/avis"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("avis", 1), entetes(ADMIN, AGENCE)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Réservé aux admins.
        assertThat(poster(DOCTEUR, "/rapports", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        script.add(r -> repondre("1. Délais de rendu : 1 demande, nouvel outil de statistiques."));
        ResponseEntity<String> r = poster(ADMIN, "/rapports", Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rapport = json.readTree(r.getBody()).path("data");
        assertThat(rapport.path("synthese").asString()).startsWith("1. Délais de rendu");
        assertThat(rapport.path("statistiques").path("besoins").toString()).contains("Délai moyen de rendu");
        assertThat(rapport.path("statistiques").path("avisNegatifs").asLong()).isPositive();
        assertThat(rapport.toString()).doesNotContain("Agossou", "Koffi", "0197112233");
        FournisseurLlm.Requete synthese = recues.getLast();
        assertThat(synthese.outils()).isNull();
        assertThat(envoye(synthese)).doesNotContain("Agossou", "Koffi", "0197112233");

        // Pas de rafale : un deuxième rapport immédiat est refusé.
        assertThat(poster(ADMIN, "/rapports", Map.of()).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(http.exchange(url("/rapports"), HttpMethod.GET, new HttpEntity<>(entetes(ADMIN, AGENCE)), String.class)
                .getBody()).contains("Délais de rendu");
    }
}

package com.labo.anapath.assistant;

import com.labo.anapath.assistant.llm.FournisseurLlm;
import com.labo.anapath.common.assistant.OutilAssistant;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le déroulement d'un tour de conversation avec l'assistant.
 *
 * <p>Un tour : on envoie l'historique au modèle ; s'il répond par du texte, le
 * tour est fini ; s'il appelle un outil de <em>lecture</em>, on l'exécute par le
 * {@link PointDePassage} et on le relance avec le résultat ; s'il appelle une
 * <em>écriture</em>, on n'exécute rien : le tour s'arrête sur une carte « À
 * confirmer », et c'est la route de confirmation qui le reprend.</p>
 *
 * <p><strong>Synchrone, sur le thread de la requête.</strong> Le contexte de
 * sécurité et l'agence ({@code BranchContext}, un ThreadLocal que lisent les
 * services d'affectation) restent posés tout le long, sans rien propager ; pas
 * de dispatch asynchrone que la chaîne de filtres de sécurité rejouerait mal.
 * Le prix : un thread Tomcat par tour en cours.</p>
 * <!-- ponytail: un thread par tour ; passer à SseEmitter + exécuteur qui propage
 *      SecurityContext et BranchContext si la concurrence l'exige un jour -->
 *
 * <p>Aucune transaction ne couvre le tour (il dure des dizaines de secondes) :
 * chaque outil passe par un service transactionnel, chaque message s'écrit seul.</p>
 */
@Slf4j
@Service
public class AssistantService {

    static final int ITERATIONS_MAX = 8;
    static final int JETONS_MAX = 16_000;
    static final int RETENTION_JOURS = 90;

    private final List<FournisseurLlm> fournisseurs;
    private final PointDePassage passage;
    private final ReglagesAssistantService reglages;
    private final ConversationRepository conversations;
    private final MessageAssistantRepository messages;
    private final ObjectMapper json;

    private final Set<UUID> occupees = ConcurrentHashMap.newKeySet();
    // ponytail: limite en mémoire, par instance — comme RateLimitFilter ; un magasin partagé si l'API passe à plusieurs instances
    private final Map<UUID, Bucket> debits = new ConcurrentHashMap<>();

    private final int messagesParMinute;
    private final int toursSimultanes;
    private final com.labo.anapath.common.security.ChiffreurDeSecrets chiffreur;
    /** Les tours en cours : un thread de requête chacun, on n'en laisse pas épuiser Tomcat. */
    private final java.util.concurrent.atomic.AtomicInteger toursEnCours = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> toursParUtilisateur = new ConcurrentHashMap<>();
    static final int TOURS_PAR_UTILISATEUR = 2;

    public AssistantService(List<FournisseurLlm> fournisseurs, PointDePassage passage,
                            ReglagesAssistantService reglages, ConversationRepository conversations,
                            MessageAssistantRepository messages, ObjectMapper json,
                            com.labo.anapath.common.security.ChiffreurDeSecrets chiffreur,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${app.assistant.messages-par-minute:10}") int messagesParMinute,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${app.assistant.tours-simultanes:20}") int toursSimultanes) {
        this.chiffreur = chiffreur;
        this.toursSimultanes = toursSimultanes;
        this.messagesParMinute = messagesParMinute;
        this.fournisseurs = fournisseurs;
        this.passage = passage;
        this.reglages = reglages;
        this.conversations = conversations;
        this.messages = messages;
        this.json = json;
    }

    /** Où écrire les événements du flux. Une {@link IOException} : le navigateur est parti. */
    @FunctionalInterface
    public interface Emetteur {
        void envoyer(String evenement, Object donnees) throws IOException;
    }

    // -------------------------------------------------------------------------
    // Conversations
    // -------------------------------------------------------------------------

    public record ConversationDto(UUID id, String titre, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record HistoriqueDto(List<MessageDto> messages, EnAttenteDto enAttente) {}

    public record MessageDto(int seq, String role, JsonNode blocs, Short avis, LocalDateTime createdAt) {}

    public record EnAttenteDto(String callId, String outil, String resume,
                               List<OutilAssistant.Confirmation.Ligne> details) {}

    public ConversationDto ouvrir(OutilAssistant.Contexte ctx) {
        ReglagesAssistant r = reglagesPrets();
        consommerDebit(ctx);
        Conversation c = new Conversation();
        c.setUserId(ctx.userId());
        c.setBranchId(ctx.branchId());
        c.setFournisseur(r.getFournisseur());
        c = conversations.save(c);
        return dto(c);
    }

    public List<ConversationDto> lister(OutilAssistant.Contexte ctx) {
        return conversations.findTop50ByUserIdAndBranchIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                ctx.userId(), ctx.branchId()).stream().map(this::dto).toList();
    }

    public HistoriqueDto historique(OutilAssistant.Contexte ctx, UUID conversationId) {
        Conversation c = charger(ctx, conversationId);
        List<MessageAssistant> tous = messages.findByConversationIdOrderBySeq(c.getId());
        // Les résultats d'un outil dont l'utilisateur n'a plus le droit ne se
        // relisent pas : retirer view-invoices doit retirer aussi ce qu'il a lu ici.
        Map<String, String> outilDeLAppel = new java.util.HashMap<>();
        tous.forEach(m -> json.readTree(m.getContenu()).forEach(b -> {
            if ("tool_use".equals(b.path("type").asString())) outilDeLAppel.put(b.path("id").asString(), b.path("name").asString());
        }));
        List<MessageDto> lignes = tous.stream()
                .filter(m -> !json.readTree(m.getContenu()).isEmpty())
                .map(m -> new MessageDto(m.getSeq(), m.getRole(), pourLEcran(json.readTree(m.getContenu()), ctx, outilDeLAppel),
                        m.getAvis(), m.getCreatedAt()))
                .toList();
        EnAttenteDto attente = null;
        if (c.getAppelEnAttenteId() != null) {
            JsonNode appel = appelDans(tous, c.getAppelEnAttenteId());
            if (appel != null) {
                var conf = passage.resumer(ctx, appel.path("name").asString(), appel.path("input"));
                attente = new EnAttenteDto(c.getAppelEnAttenteId(), appel.path("name").asString(),
                        conf.resume(), conf.details());
            }
        }
        return new HistoriqueDto(lignes, attente);
    }

    /**
     * Les blocs dans la forme du contrat de l'écran : {@code {type:text,texte}},
     * {@code {type:tool_use,id,nom,args}}, {@code {type:tool_result,callId,contenu,erreur}}.
     * La forme interne reste celle des fournisseurs ; elle ne sort pas d'ici.
     */
    private JsonNode pourLEcran(JsonNode blocs, OutilAssistant.Contexte ctx, Map<String, String> outilDeLAppel) {
        ArrayNode sortie = json.createArrayNode();
        for (JsonNode b : blocs) {
            switch (b.path("type").asString()) {
                case "text" -> sortie.addObject().put("type", "text").put("texte", b.path("text").asString());
                case "tool_use" -> sortie.addObject().put("type", "tool_use").put("id", b.path("id").asString())
                        .put("nom", b.path("name").asString()).set("args", b.path("input"));
                case "tool_result" -> {
                    ObjectNode r = sortie.addObject().put("type", "tool_result")
                            .put("callId", b.path("tool_use_id").asString());
                    OutilAssistant outil = passage.outil(outilDeLAppel.getOrDefault(b.path("tool_use_id").asString(), ""));
                    boolean permis = outil == null || ctx.permissions().contains(outil.permission());
                    if (permis) r.set("contenu", b.path("content"));
                    else r.putObject("contenu").put("masque", "Vous n'avez plus accès à cette information.");
                    r.put("erreur", b.path("is_error").asBoolean());
                }
                default -> { /* rien d'autre n'est montré */ }
            }
        }
        return sortie;
    }

    /**
     * Le 👍 / 👎 sur une réponse de l'assistant. Le commentaire est masqué avec la
     * table de la conversation, comme ce qui part chez le fournisseur : il est relu
     * par le rapport d'amélioration, qui ne doit pas porter de nom en clair.
     */
    @Transactional
    public void donnerAvis(OutilAssistant.Contexte ctx, UUID conversationId, int seq, int avis, String commentaire) {
        Conversation c = charger(ctx, conversationId);
        List<MessageAssistant> tous = messages.findByConversationIdOrderBySeq(c.getId());
        MessageAssistant m = tous.stream().filter(x -> x.getSeq() == seq && "assistant".equals(x.getRole()))
                .findFirst().orElseThrow(() -> new AssistantException(HttpStatus.NOT_FOUND, "introuvable", "Réponse introuvable."));
        m.setAvis(avis == 0 ? null : (short) Integer.signum(avis));
        String propre = commentaire == null ? null : commentaire.strip();
        boolean masquage = reglages.courants().isCaviardage();
        if (propre == null || propre.isEmpty() || avis == 0 || (masquage && !chiffreur.estActif())) {
            // Sans clé, on ne saurait pas masquer : le commentaire n'est pas gardé.
            m.setAvisCommentaire(null);
        } else {
            Caviardeur cav = new Caviardeur(masquage, masquage ? chiffreur.deriver("assistant-caviardage") : null);
            tous.forEach(x -> cav.apprendre(json.readTree(x.getContenu())));
            ArrayNode bloc = json.createArrayNode();
            bloc.addObject().put("type", "text").put("text", propre);
            m.setAvisCommentaire(cav.masquer(bloc).get(0).path("text").asString());
        }
        messages.save(m);
    }

    // -------------------------------------------------------------------------
    // Un tour
    // -------------------------------------------------------------------------

    /**
     * Ce qu'un tour a besoin de savoir, vérifié avant le premier octet du flux :
     * tout refus lève une {@link AssistantException} rendue en JSON ordinaire.
     */
    public record Preparation(OutilAssistant.Contexte ctx, PointDePassage.Origine origine,
                              Conversation conversation, ReglagesAssistant reglages) {}

    public Preparation preparer(OutilAssistant.Contexte ctx, UUID conversationId, String ip, String empreinte) {
        ReglagesAssistant r = reglagesPrets();
        Conversation c = charger(ctx, conversationId);
        if (!r.getFournisseur().equals(c.getFournisseur())) {
            throw new AssistantException(HttpStatus.CONFLICT, "fournisseur_change",
                    "Cette conversation a été ouverte avec un autre fournisseur. Ouvrez une nouvelle conversation.");
        }
        Long quota = r.getQuotaMensuelJetons();
        if (quota != null && reglages.consommeCeMois() >= quota) {
            throw new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "budget_atteint",
                    "Le quota mensuel de l'assistant est atteint.");
        }
        if (r.isCaviardage() && !chiffreur.estActif()) {
            throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "chiffrement_absent",
                    "Le masquage exige la clé de chiffrement des secrets (SECRETS_KEY) sur le serveur.");
        }
        consommerDebit(ctx);
        var miens = toursParUtilisateur.computeIfAbsent(ctx.userId(), u -> new java.util.concurrent.atomic.AtomicInteger());
        if (miens.incrementAndGet() > TOURS_PAR_UTILISATEUR) {
            miens.decrementAndGet();
            throw new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "trop_de_tours",
                    "Vous avez déjà des réponses en cours. Attendez qu'elles se terminent.");
        }
        if (toursEnCours.incrementAndGet() > toursSimultanes) {
            toursEnCours.decrementAndGet();
            miens.decrementAndGet();
            throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "sature",
                    "L'assistant est très sollicité. Réessayez dans un instant.");
        }
        if (!occupees.add(c.getId())) {
            toursEnCours.decrementAndGet();
            miens.decrementAndGet();
            throw new AssistantException(HttpStatus.CONFLICT, "occupee",
                    "Une réponse est déjà en cours dans cette conversation.");
        }
        return new Preparation(ctx, new PointDePassage.Origine(c.getId(), ip, empreinte), c, r);
    }

    private void consommerDebit(OutilAssistant.Contexte ctx) {
        Bucket debit = debits.computeIfAbsent(ctx.userId(), u -> Bucket.builder()
                .addLimit(Bandwidth.classic(messagesParMinute, Refill.intervally(messagesParMinute, Duration.ofMinutes(1)))).build());
        if (!debit.tryConsume(1)) {
            throw new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "trop_de_messages",
                    "Trop de messages en peu de temps. Réessayez dans une minute.");
        }
    }

    /** Un message de l'utilisateur, puis le tour jusqu'à sa fin ou à une confirmation. */
    public void envoyer(Preparation p, String texte, Emetteur sortie) {
        try {
            Session s = session(p);
            if (p.conversation().getAppelEnAttenteId() != null) {
                // L'utilisateur est passé à autre chose sans trancher. Les deux
                // fournisseurs refusent un appel d'outil resté sans réponse : on
                // en écrit une, qui dit que rien n'a été fait.
                String appelId = p.conversation().getAppelEnAttenteId();
                p.conversation().setAppelEnAttenteId(null);
                if (conversations.prendreLAppel(p.conversation().getId(), appelId) == 1) {
                    JsonNode appel = appelDans(s.historique, appelId);
                    if (appel != null) {
                        passage.tracerSansExecuter(p.ctx(), p.origine(), appel.path("name").asString(),
                                appel.path("input"), AppelOutil.Statut.REJETE);
                    }
                    s.ajouterResultat(appelId, Map.of("statut",
                            "Non exécuté : l'utilisateur a écrit un nouveau message sans confirmer."), false);
                }
            }
            if (p.conversation().getTitre() == null) {
                String t = texte.strip();
                p.conversation().setTitre(t.length() > 80 ? t.substring(0, 80) + "…" : t);
            }
            ArrayNode contenu = json.createArrayNode();
            contenu.addObject().put("type", "text").put("text", texte);
            ArrayNode envoye = json.createArrayNode();
            // La date du jour vit dans le message, pas dans la consigne : la
            // consigne doit rester identique d'un tour à l'autre (cache), et le
            // message, une fois parti, ne change plus.
            envoye.addObject().put("type", "text")
                    .put("text", "[" + LocalDate.now() + "] " + s.caviardeur.masquer(contenu).get(0).path("text").asString());
            s.ajouter("user", contenu, envoye, 0, 0);
            boucle(s, sortie);
        } finally {
            liberer(p);
        }
    }

    /** La réponse de l'utilisateur à une carte « À confirmer », puis la suite du tour. */
    public void confirmer(Preparation p, String appelId, boolean accepter, Emetteur sortie) {
        try {
            if (conversations.prendreLAppel(p.conversation().getId(), appelId) != 1) {
                throw new AssistantException(HttpStatus.CONFLICT, "deja_tranchee",
                        "Cette action a déjà été confirmée ou refusée.");
            }
            // L'objet en mémoire porte encore l'appel : le prochain save le réécrirait.
            p.conversation().setAppelEnAttenteId(null);
            Session s = session(p);
            JsonNode appel = appelDans(s.historique, appelId);
            if (appel == null) {
                throw new AssistantException(HttpStatus.NOT_FOUND, "appel_introuvable", "Action introuvable.");
            }
            String nom = appel.path("name").asString();
            emettre(sortie, "tool_call", Map.of("callId", appelId, "outil", nom, "libelle", libelle(nom)));
            if (accepter) {
                var issue = passage.appeler(p.ctx(), p.origine(), nom, appel.path("input"), AppelOutil.Statut.CONFIRME);
                s.ajouterResultat(appelId, issue.donnees(), !issue.ok());
                emettreResultat(sortie, appelId, nom, issue);
            } else {
                passage.tracerSansExecuter(p.ctx(), p.origine(), nom, appel.path("input"), AppelOutil.Statut.REJETE);
                Map<String, String> refus = Map.of("statut", "Refusé par l'utilisateur : rien n'a été modifié.");
                s.ajouterResultat(appelId, refus, false);
                emettreResultat(sortie, appelId, nom, new PointDePassage.Issue(false, refus, Map.of()));
            }
            boucle(s, sortie);
        } finally {
            liberer(p);
        }
    }

    public void liberer(Preparation p) {
        if (occupees.remove(p.conversation().getId())) {
            toursEnCours.decrementAndGet();
            var miens = toursParUtilisateur.get(p.ctx().userId());
            if (miens != null) miens.decrementAndGet();
        }
    }

    private void boucle(Session s, Emetteur sortie) {
        FournisseurLlm fournisseur = fournisseur(s.reglages.getFournisseur());
        List<OutilAssistant> disponibles = passage.outilsPour(s.ctx, s.caviardeur.actif());
        ArrayNode schemas = passage.schemas(disponibles);
        try {
            for (int i = 0; i < ITERATIONS_MAX; i++) {
                Long quota = s.reglages.getQuotaMensuelJetons();
                if (quota != null && reglages.consommeCeMois() >= quota) {
                    emettre(sortie, "budget_exceeded", Map.of());
                    return;
                }
                var tour = new Tour(s.caviardeur);
                fournisseur.completer(new FournisseurLlm.Requete(s.reglages.getCleApi(), s.reglages.getModele(),
                        ConsigneAssistant.TEXTE, s.envoyes(), schemas, JETONS_MAX), ev -> {
                    switch (ev) {
                        case FournisseurLlm.TexteDelta d -> tour.texte(d.texte(), seg -> emettre(sortie, "token", Map.of("texte", seg)));
                        case FournisseurLlm.BlocTermine b -> tour.blocs.add(b.bloc());
                        case FournisseurLlm.Consommation c -> { tour.entree = c.entree(); tour.sortie = c.sortie(); }
                        case FournisseurLlm.Fin f -> tour.raison = f.raison();
                    }
                });
                tour.vider(seg -> emettre(sortie, "token", Map.of("texte", seg)));
                emettre(sortie, "usage", Map.of("entree", tour.entree, "sortie", tour.sortie));

                if ("refusal".equals(tour.raison)) {
                    // Refus des classifieurs : le texte partiel n'est pas une réponse.
                    s.compterSeulement(tour.entree, tour.sortie);
                    emettre(sortie, "error", Map.of("code", "refus",
                            "message", "Le fournisseur a refusé de répondre à cette demande. Reformulez-la."));
                    return;
                }
                s.ajouter("assistant", tour.clair(json), tour.envoye(json), tour.entree, tour.sortie);

                JsonNode appel = tour.premierAppel();
                if (appel == null) {
                    emettre(sortie, "done", Map.of("seq", s.historique.getLast().getSeq()));
                    return;
                }
                String appelId = appel.path("id").asString();
                String nom = appel.path("name").asString();
                // Le signalement d'un besoin garde la forme masquée : il nourrit le
                // rapport d'amélioration, qui ne doit pas porter de nom en clair.
                JsonNode args = OutilSignalerBesoin.NOM.equals(nom) ? appel.path("input")
                        : s.caviardeur.retablir(appel.path("input"));
                OutilAssistant outil = passage.outil(nom);

                if (outil != null && outil.categorie() == OutilAssistant.Categorie.ECRITURE
                        && s.ctx.permissions().contains(outil.permission())) {
                    var conf = passage.resumer(s.ctx, nom, args);
                    passage.tracerSansExecuter(s.ctx, s.origine, nom, args, AppelOutil.Statut.EN_ATTENTE);
                    s.conversation.setAppelEnAttenteId(appelId);
                    conversations.save(s.conversation);
                    emettre(sortie, "confirmation_required", new EnAttenteDto(appelId, nom, conf.resume(), conf.details()));
                    return;
                }
                emettre(sortie, "tool_call", Map.of("callId", appelId, "outil", nom, "libelle", libelle(nom)));
                var issue = passage.appeler(s.ctx, s.origine, nom, args, AppelOutil.Statut.SUCCES);
                s.ajouterResultat(appelId, issue.donnees(), !issue.ok());
                emettreResultat(sortie, appelId, nom, issue);
            }
            emettre(sortie, "error", Map.of("code", "trop_d_etapes",
                    "message", "La demande demande trop d'étapes. Précisez-la."));
        } catch (UncheckedIOException parti) {
            log.debug("Flux de l'assistant interrompu par le navigateur");
        } catch (FournisseurLlm.Indisponible e) {
            emettreSansEchec(sortie, "error", Map.of("code", "fournisseur_indisponible", "message", e.getMessage()));
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("Fournisseur injoignable : {}", e.getMessage());
            emettreSansEchec(sortie, "error", Map.of("code", "fournisseur_indisponible",
                    "message", "Le fournisseur d'IA ne répond pas. Réessayez."));
        } catch (RuntimeException e) {
            log.error("Tour de l'assistant en échec", e);
            emettreSansEchec(sortie, "error", Map.of("code", "erreur", "message", "La réponse a été interrompue."));
        }
    }

    // -------------------------------------------------------------------------
    // Session : l'historique d'une conversation et sa table de masquage
    // -------------------------------------------------------------------------

    private Session session(Preparation p) {
        List<MessageAssistant> historique = new ArrayList<>(messages.findByConversationIdOrderBySeq(p.conversation().getId()));
        Caviardeur cav = new Caviardeur(p.reglages().isCaviardage(), chiffreur.deriver("assistant-caviardage"));
        // La table se reconstruit à l'identique : les jetons sont des empreintes.
        historique.forEach(m -> cav.apprendre(json.readTree(m.getContenu())));
        return new Session(p.ctx(), p.origine(), p.conversation(), p.reglages(), historique, cav);
    }

    private final class Session {
        final OutilAssistant.Contexte ctx;
        final PointDePassage.Origine origine;
        final Conversation conversation;
        final ReglagesAssistant reglages;
        final List<MessageAssistant> historique;
        final Caviardeur caviardeur;

        Session(OutilAssistant.Contexte ctx, PointDePassage.Origine origine, Conversation conversation,
                ReglagesAssistant reglages, List<MessageAssistant> historique, Caviardeur caviardeur) {
            this.ctx = ctx;
            this.origine = origine;
            this.conversation = conversation;
            this.reglages = reglages;
            this.historique = historique;
            this.caviardeur = caviardeur;
        }

        List<JsonNode> envoyes() {
            List<JsonNode> liste = new ArrayList<>();
            for (MessageAssistant m : historique) {
                ObjectNode n = json.createObjectNode().put("role", m.getRole());
                n.set("content", json.readTree(m.getEnvoye()));
                liste.add(n);
            }
            return liste;
        }

        void ajouter(String role, JsonNode contenu, JsonNode envoye, int entree, int sortie) {
            MessageAssistant m = new MessageAssistant();
            m.setConversationId(conversation.getId());
            m.setSeq(historique.isEmpty() ? 1 : historique.getLast().getSeq() + 1);
            m.setRole(role);
            m.setContenu(contenu.toString());
            m.setEnvoye(envoye.toString());
            m.setJetonsEntree(entree);
            m.setJetonsSortie(sortie);
            m.setCreatedAt(LocalDateTime.now());
            historique.add(messages.save(m));
            caviardeur.apprendre(contenu);
            // La conversation remonte en tête de liste et échappe à la purge.
            conversations.save(conversation);
        }

        void ajouterResultat(String appelId, Object donnees, boolean erreur) {
            ArrayNode contenu = json.createArrayNode();
            ObjectNode bloc = contenu.addObject().put("type", "tool_result").put("tool_use_id", appelId);
            bloc.set("content", json.valueToTree(donnees));
            bloc.put("is_error", erreur);
            caviardeur.apprendre(contenu);
            ajouter("tool", contenu, caviardeur.masquer(contenu), 0, 0);
        }

        /** Un refus coûte quand même des jetons : on les compte sans garder de message. */
        void compterSeulement(int entree, int sortie) {
            if (entree == 0 && sortie == 0) return;
            // Un message assistant vide casserait l'historique : on le range en
            // « tool » sans bloc, que les deux fournisseurs ignorent.
            ajouter("tool", json.createArrayNode(), json.createArrayNode(), entree, sortie);
        }
    }

    /** Ce que le modèle a produit pendant un appel. */
    private static final class Tour {
        final Caviardeur caviardeur;
        final List<JsonNode> blocs = new ArrayList<>();
        int entree;
        int sortie;
        String raison = "end_turn";
        private final StringBuilder enAttente = new StringBuilder();

        Tour(Caviardeur caviardeur) {
            this.caviardeur = caviardeur;
        }

        /**
         * Le texte part à l'écran par segments coupés sur un blanc : un jeton
         * ({@code NOM_3fa2c1}) n'a pas d'espace et arrive donc entier dans un
         * segment, où il peut être rétabli — morceau par morceau, il serait coupé.
         */
        void texte(String delta, java.util.function.Consumer<String> afficher) {
            enAttente.append(delta);
            int coupe = Math.max(enAttente.lastIndexOf(" "), enAttente.lastIndexOf("\n"));
            if (coupe >= 0) {
                afficher.accept(caviardeur.retablir(enAttente.substring(0, coupe + 1)));
                enAttente.delete(0, coupe + 1);
            }
        }

        void vider(java.util.function.Consumer<String> afficher) {
            if (!enAttente.isEmpty()) afficher.accept(caviardeur.retablir(enAttente.toString()));
            enAttente.setLength(0);
        }

        /** Ce qui repart chez le fournisseur : les blocs tels qu'il les a produits. */
        ArrayNode envoye(ObjectMapper json) {
            ArrayNode a = json.createArrayNode();
            blocs.forEach(a::add);
            return a;
        }

        /** Ce que l'écran relit : texte et appels rétablis, sans la réflexion. */
        ArrayNode clair(ObjectMapper json) {
            ArrayNode a = json.createArrayNode();
            for (JsonNode b : blocs) {
                String type = b.path("type").asString();
                if ("text".equals(type)) {
                    a.addObject().put("type", "text").put("text", caviardeur.retablir(b.path("text").asString()));
                } else if ("tool_use".equals(type)) {
                    ObjectNode c = a.addObject().put("type", "tool_use").put("id", b.path("id").asString())
                            .put("name", b.path("name").asString());
                    c.set("input", caviardeur.retablir(b.path("input")));
                }
            }
            return a;
        }

        JsonNode premierAppel() {
            return blocs.stream().filter(b -> "tool_use".equals(b.path("type").asString())).findFirst().orElse(null);
        }
    }

    // -------------------------------------------------------------------------
    // Outillage
    // -------------------------------------------------------------------------

    private ReglagesAssistant reglagesPrets() {
        ReglagesAssistant r = reglages.courants();
        if (!ReglagesAssistantService.pret(r)) {
            throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "non_configure",
                    "L'assistant n'est pas encore configuré.");
        }
        if (fournisseur(r.getFournisseur()) == null) {
            throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "non_configure",
                    "Fournisseur inconnu : " + r.getFournisseur());
        }
        return r;
    }

    private FournisseurLlm fournisseur(String code) {
        return fournisseurs.stream().filter(f -> code.equals(f.code())).findFirst().orElse(null);
    }

    private Conversation charger(OutilAssistant.Contexte ctx, UUID id) {
        return conversations.findByIdAndUserIdAndBranchIdAndDeletedAtIsNull(id, ctx.userId(), ctx.branchId())
                .orElseThrow(() -> new AssistantException(HttpStatus.NOT_FOUND, "introuvable", "Conversation introuvable."));
    }

    /** L'appel d'outil {@code appelId} dans le dernier message assistant qui le porte (forme en clair). */
    private JsonNode appelDans(List<MessageAssistant> historique, String appelId) {
        for (int i = historique.size() - 1; i >= 0; i--) {
            MessageAssistant m = historique.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            for (JsonNode b : json.readTree(m.getContenu())) {
                if ("tool_use".equals(b.path("type").asString()) && appelId.equals(b.path("id").asString())) return b;
            }
        }
        return null;
    }

    private String libelle(String nom) {
        OutilAssistant o = passage.outil(nom);
        return o == null ? nom : o.libelle();
    }

    private void emettreResultat(Emetteur sortie, String appelId, String nom, PointDePassage.Issue issue) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("callId", appelId);
        d.put("outil", nom);
        d.put("ok", issue.ok());
        d.put("donnees", issue.donnees());
        if (!issue.ui().isEmpty()) d.put("ui", issue.ui());
        emettre(sortie, "tool_result", d);
    }

    private static void emettre(Emetteur sortie, String evenement, Object donnees) {
        try {
            sortie.envoyer(evenement, donnees);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void emettreSansEchec(Emetteur sortie, String evenement, Object donnees) {
        try {
            sortie.envoyer(evenement, donnees);
        } catch (IOException ignore) {
            // le navigateur est déjà parti
        }
    }

    private ConversationDto dto(Conversation c) {
        return new ConversationDto(c.getId(), c.getTitre(), c.getCreatedAt(), c.getUpdatedAt());
    }

    /** Purge des conversations inactives depuis {@value #RETENTION_JOURS} jours (données de santé en clair). */
    @Transactional
    public int purger() {
        LocalDateTime avant = LocalDateTime.now().minusDays(RETENTION_JOURS);
        messages.purgerAvant(avant);
        return conversations.purger(avant);
    }
}

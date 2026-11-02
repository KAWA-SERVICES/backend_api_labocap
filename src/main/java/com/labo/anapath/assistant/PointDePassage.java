package com.labo.anapath.assistant;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.audit.JournalAcces;
import com.labo.anapath.common.audit.JournalAccesService;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ReaffectationNonConfirmeeException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Le seul chemin par lequel l'assistant touche aux données.
 *
 * <p>Dans l'ordre, à chaque appel : l'outil existe ; l'utilisateur détient la
 * permission de la route HTTP équivalente ; les arguments se lisent et se
 * valident ; l'outil s'exécute ; l'appel est tracé (succès, échec ou refus) ;
 * chaque dossier lu est inscrit au journal des accès, comme s'il avait été
 * ouvert à l'écran. Les services métier ne portent pas de {@code @PreAuthorize} :
 * c'est ici que le droit se vérifie, avec le principal de la requête en cours —
 * y compris au moment où une écriture est confirmée.</p>
 */
@Slf4j
@Component
public class PointDePassage {

    private final Map<String, OutilAssistant> outils;
    private final ObjectMapper json;
    private final Validator validateur;
    private final AppelOutilRepository appels;
    private final JournalAccesService journal;

    public PointDePassage(List<OutilAssistant> declares, ObjectMapper json, Validator validateur,
                          AppelOutilRepository appels, JournalAccesService journal) {
        this.outils = new LinkedHashMap<>();
        for (OutilAssistant o : declares) {
            if (!o.nom().matches("[a-zA-Z0-9_-]{1,64}")) {
                throw new IllegalStateException("Nom d'outil refusé par les fournisseurs : " + o.nom());
            }
            if (outils.putIfAbsent(o.nom(), o) != null) {
                throw new IllegalStateException("Outil déclaré deux fois : " + o.nom());
            }
        }
        this.json = json;
        this.validateur = validateur;
        this.appels = appels;
        this.journal = journal;
    }

    /** Ce que l'appelant de la requête a le droit d'appeler, et que le masquage autorise. */
    public List<OutilAssistant> outilsPour(OutilAssistant.Contexte ctx, boolean caviardage) {
        return outils.values().stream()
                .filter(o -> ctx.permissions().contains(o.permission()))
                .filter(o -> !(caviardage && o.texteLibre()))
                .toList();
    }

    public ArrayNode schemas(List<OutilAssistant> liste) {
        ArrayNode tableau = json.createArrayNode();
        for (OutilAssistant o : liste) {
            tableau.addObject().put("name", o.nom()).put("description", o.description())
                    .set("input_schema", json.readTree(o.schemaJson()));
        }
        return tableau;
    }

    public java.util.Collection<OutilAssistant> tous() {
        return java.util.Collections.unmodifiableCollection(outils.values());
    }

    public OutilAssistant outil(String nom) {
        return outils.get(nom);
    }

    /** Le résultat d'un appel, tel que le moteur le renvoie au modèle et à l'écran. */
    public record Issue(boolean ok, Object donnees, Map<String, String> ui) {
        static Issue echec(String message) {
            return new Issue(false, Map.of("erreur", message), Map.of());
        }
    }

    /** Où l'appel a lieu : pour la trace et le journal. */
    public record Origine(UUID conversationId, String ip, String empreinteNavigateur) {}

    public Issue appeler(OutilAssistant.Contexte ctx, Origine origine, String nom, JsonNode args,
                         AppelOutil.Statut statutDuSucces) {
        long debut = System.nanoTime();
        OutilAssistant outil = outils.get(nom);
        if (outil == null) {
            // Arguments non conservés : on ne sait pas ce qu'ils contiennent.
            tracer(ctx, origine, nom, "INCONNU", null, AppelOutil.Statut.REFUSE, "outil inconnu", debut);
            return Issue.echec("Outil inconnu.");
        }
        if (!ctx.permissions().contains(outil.permission())) {
            tracer(ctx, origine, nom, outil.categorie().name(), args, AppelOutil.Statut.REFUSE,
                    "permission manquante : " + outil.permission(), debut);
            return Issue.echec("L'utilisateur n'a pas le droit d'accéder à cette information ("
                    + outil.permission() + ").");
        }
        Object lus;
        try {
            lus = lire(outil, args);
        } catch (IllegalArgumentException e) {
            tracer(ctx, origine, nom, outil.categorie().name(), null, AppelOutil.Statut.ECHEC, e.getMessage(), debut);
            return Issue.echec("Arguments invalides : " + e.getMessage());
        }
        try {
            @SuppressWarnings("unchecked")
            var executeur = (OutilAssistant.Executeur<Object>) outil.executeur();
            OutilAssistant.Resultat r = executeur.executer(ctx, lus);
            tracer(ctx, origine, nom, outil.categorie().name(), args, statutDuSucces, null, debut);
            for (OutilAssistant.Lecture l : r.lectures()) {
                journal.enregistrer(ctx.userId(), ctx.branchId(), JournalAcces.Action.READ,
                        JournalAcces.Entite.valueOf(l.entite()), l.id().toString(),
                        origine.ip(), origine.empreinteNavigateur());
            }
            return new Issue(true, r.donnees(), r.ui());
        } catch (ResourceNotFoundException e) {
            tracer(ctx, origine, nom, outil.categorie().name(), args, AppelOutil.Statut.ECHEC, "introuvable", debut);
            return Issue.echec("Introuvable dans l'agence de l'utilisateur.");
        } catch (BusinessException | InvalidOperationException | ReaffectationNonConfirmeeException e) {
            // Le message métier peut citer une valeur : la trace ne garde que la classe.
            tracer(ctx, origine, nom, outil.categorie().name(), args, AppelOutil.Statut.ECHEC,
                    e.getClass().getSimpleName(), debut);
            return Issue.echec(e.getMessage());
        } catch (RuntimeException e) {
            // Ni message ni pile au journal : une erreur SQL peut citer des valeurs du dossier.
            log.error("Outil {} en échec : {} ({})", nom, e.getClass().getName(),
                    e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "?");
            tracer(ctx, origine, nom, outil.categorie().name(), args, AppelOutil.Statut.ECHEC,
                    e.getClass().getSimpleName(), debut);
            return Issue.echec("L'opération n'a pas abouti.");
        }
    }

    /**
     * La carte de confirmation d'une écriture, calculée par le serveur à partir
     * des arguments rétablis — jamais reprise du texte du modèle.
     */
    public OutilAssistant.Confirmation resumer(OutilAssistant.Contexte ctx, String nom, JsonNode args) {
        OutilAssistant outil = outils.get(nom);
        if (outil == null || outil.resumeur() == null) {
            return new OutilAssistant.Confirmation("Action à confirmer", List.of());
        }
        try {
            @SuppressWarnings("unchecked")
            var resumeur = (OutilAssistant.Resumeur<Object>) outil.resumeur();
            return resumeur.resumer(ctx, lire(outil, args));
        } catch (RuntimeException e) {
            // Le résumé ne doit pas empêcher de refuser : on montre ce qu'on a.
            String detail = e instanceof BusinessException || e instanceof ResourceNotFoundException
                    || e instanceof IllegalArgumentException ? e.getMessage() : "Détail indisponible.";
            return new OutilAssistant.Confirmation(outil.libelle(), List.of(
                    new OutilAssistant.Confirmation.Ligne("Détail", detail)));
        }
    }

    /** Trace un appel d'écriture laissé en attente, puis refusé ou abandonné. */
    public void tracerSansExecuter(OutilAssistant.Contexte ctx, Origine origine, String nom, JsonNode args,
                                   AppelOutil.Statut statut) {
        OutilAssistant outil = outils.get(nom);
        tracer(ctx, origine, nom, outil == null ? "INCONNU" : outil.categorie().name(), args, statut, null,
                System.nanoTime());
    }

    private Object lire(OutilAssistant outil, JsonNode args) {
        Object lus;
        try {
            lus = json.treeToValue(args == null || args.isNull() ? json.createObjectNode() : args, outil.typeArgs());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("format inattendu");
        }
        var violations = validateur.validate(lus);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted().collect(Collectors.joining(", ")));
        }
        return lus;
    }

    /**
     * Le texte recherché est souvent un nom de patient tapé par l'utilisateur :
     * la trace, conservée au-delà de la purge des conversations, n'en garde pas
     * le texte — seulement qu'il y en avait un. Les identifiants restent.
     */
    private static final java.util.Set<String> TEXTES_LIBRES = java.util.Set.of("recherche", "demande", "commentaire");

    private JsonNode pourLaTrace(String nom, JsonNode args) {
        if (args == null || !args.isObject()) return null;
        OutilAssistant outil = outils.get(nom);
        java.util.Set<String> declares = new java.util.HashSet<>();
        if (outil != null && outil.typeArgs().isRecord()) {
            for (var c : outil.typeArgs().getRecordComponents()) declares.add(c.getName());
        }
        var copie = json.createObjectNode();
        for (var champ : args.properties()) {
            // Seuls les champs déclarés : une clé inattendue pourrait porter un nom rétabli.
            if (!declares.contains(champ.getKey())) continue;
            if (TEXTES_LIBRES.contains(champ.getKey())) copie.put(champ.getKey(), "[non conservé]");
            else copie.set(champ.getKey(), champ.getValue());
        }
        return copie;
    }

    private void tracer(OutilAssistant.Contexte ctx, Origine origine, String nom, String categorie, JsonNode args,
                        AppelOutil.Statut statut, String erreur, long debut) {
        AppelOutil a = new AppelOutil();
        a.setConversationId(origine.conversationId());
        a.setUserId(ctx.userId());
        a.setBranchId(ctx.branchId());
        a.setOutil(nom.length() > 100 ? nom.substring(0, 100) : nom);
        a.setCategorie(categorie);
        JsonNode trace = pourLaTrace(nom, args);
        a.setArguments(trace == null ? null : trace.toString());
        a.setStatut(statut);
        a.setErreur(erreur);
        a.setDureeMs((int) ((System.nanoTime() - debut) / 1_000_000));
        a.setCreatedAt(LocalDateTime.now());
        appels.save(a);
    }
}

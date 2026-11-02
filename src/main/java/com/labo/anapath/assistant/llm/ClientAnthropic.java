package com.labo.anapath.assistant.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * API Messages d'Anthropic, en flux, par HTTP direct.
 *
 * <p>Pas de SDK : il apporterait Kotlin, OkHttp et Jackson 2 à côté du Jackson 3
 * de Spring Boot 4, autant d'arbres de dépendances pour l'analyse de
 * vulnérabilités du CI. Le format du flux est stable et court à lire.</p>
 *
 * <p>Version d'API {@value #VERSION_API} : {@code temperature}, {@code top_p}
 * et {@code top_k} y sont refusés, on ne les envoie donc jamais. La réflexion
 * des modèles récents est toujours active : ses blocs arrivent dans la réponse
 * et doivent revenir <em>à l'identique</em> au tour suivant — d'où la
 * conservation verbatim des blocs inconnus.</p>
 */
@Slf4j
@Component
public class ClientAnthropic implements FournisseurLlm {

    static final String VERSION_API = "2026-10-21";
    private static final String BETA_REPLI = "server-side-fallback-2026-07-01";

    private final RestClient http;
    private final ObjectMapper json;

    public ClientAnthropic(RestClient.Builder builder, ObjectMapper json,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${app.assistant.anthropic-url:https://api.anthropic.com}") String url) {
        this.http = HttpAssistant.client(builder, url);
        this.json = json;
    }

    @Override
    public String code() {
        return "ANTHROPIC";
    }

    @Override
    public void completer(Requete r, Consumer<Evenement> sortie) {
        ObjectNode corps = corps(r);
        var requete = http.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", r.cleApi())
                .header("anthropic-version", VERSION_API);
        if (accepteLeRepli(r.modele())) requete = requete.header("anthropic-beta", BETA_REPLI);
        requete.body(json.writeValueAsString(corps)).exchange((req, res) -> {
            if (res.getStatusCode().isError()) {
                throw HttpAssistant.erreur("Anthropic", res.getStatusCode().value(), res.getBody());
            }
            lireFlux(res.getBody(), sortie);
            return null;
        });
    }

    /**
     * Le repli côté serveur : un refus des classifieurs de sécurité est rejoué
     * sur le modèle qu'Anthropic recommande pour cette catégorie, dans le même
     * flux. Pertinent ici — l'anatomopathologie touche à la biologie, et les
     * classifieurs « bio » peuvent se tromper sur un contenu médical bénin.
     * Haiku n'a pas de repli : on ne l'envoie pas.
     */
    static boolean accepteLeRepli(String modele) {
        return modele != null && (modele.startsWith("claude-opus-5") || modele.startsWith("claude-fable-5")
                || modele.startsWith("claude-sonnet-5-5"));
    }

    ObjectNode corps(Requete r) {
        ObjectNode corps = json.createObjectNode();
        corps.put("model", r.modele());
        corps.put("max_tokens", r.maxJetons());
        corps.put("stream", true);
        corps.put("system", r.systeme());
        // Mise en cache automatique du préfixe : outils, consigne et historique
        // ne changent pas d'un tour à l'autre, seul le dernier message s'ajoute.
        corps.putObject("cache_control").put("type", "ephemeral");
        corps.set("messages", messages(r.messages()));
        if (r.outils() != null && !r.outils().isEmpty()) {
            corps.set("tools", r.outils());
            // Un appel d'outil par tour : une écriture suspend le tour, et un
            // deuxième appel parti en parallèle n'aurait pas de réponse.
            corps.putObject("tool_choice").put("type", "auto").put("disable_parallel_tool_use", true);
        }
        if (accepteLeRepli(r.modele())) corps.put("fallbacks", "default");
        return corps;
    }

    /** Le rôle {@code tool} devient {@code user} ; deux messages de même rôle fusionnent. */
    ArrayNode messages(List<JsonNode> neutres) {
        ArrayNode sortie = json.createArrayNode();
        ObjectNode dernier = null;
        for (JsonNode m : neutres) {
            String role = "assistant".equals(m.path("role").asString()) ? "assistant" : "user";
            ArrayNode contenu = json.createArrayNode();
            for (JsonNode bloc : m.path("content")) contenu.add(versAnthropic(bloc));
            if (contenu.isEmpty()) continue;
            if (dernier != null && role.equals(dernier.path("role").asString())) {
                ((ArrayNode) dernier.get("content")).addAll(contenu);
            } else {
                dernier = json.createObjectNode();
                dernier.put("role", role);
                dernier.set("content", contenu);
                sortie.add(dernier);
            }
        }
        return sortie;
    }

    private JsonNode versAnthropic(JsonNode bloc) {
        if (!"tool_result".equals(bloc.path("type").asString())) return bloc;
        ObjectNode copie = ((ObjectNode) bloc).deepCopy();
        JsonNode contenu = bloc.get("content");
        copie.put("content", contenu == null ? "" : contenu.isString() ? contenu.asString() : contenu.toString());
        return copie;
    }

    void lireFlux(InputStream flux, Consumer<Evenement> sortie) throws IOException {
        var blocs = new TreeMap<Integer, ObjectNode>();
        var entrees = new TreeMap<Integer, StringBuilder>();
        int[] jetons = new int[2];
        String[] raison = {"end_turn"};
        List<Integer> replis = new ArrayList<>();

        LecteurSse.lire(flux, (evenement, donnees) -> {
            JsonNode e = json.readTree(donnees);
            switch (e.path("type").asString()) {
                case "message_start" -> {
                    JsonNode u = e.path("message").path("usage");
                    jetons[0] = u.path("input_tokens").asInt() + u.path("cache_creation_input_tokens").asInt()
                            + u.path("cache_read_input_tokens").asInt();
                }
                case "content_block_start" -> {
                    int i = e.path("index").asInt();
                    ObjectNode bloc = ((ObjectNode) e.path("content_block")).deepCopy();
                    if ("fallback".equals(bloc.path("type").asString())) replis.add(i);
                    if ("tool_use".equals(bloc.path("type").asString())) entrees.put(i, new StringBuilder());
                    blocs.put(i, bloc);
                }
                case "content_block_delta" -> {
                    int i = e.path("index").asInt();
                    ObjectNode bloc = blocs.get(i);
                    JsonNode d = e.path("delta");
                    if (bloc == null) return;
                    switch (d.path("type").asString()) {
                        case "text_delta" -> {
                            String t = d.path("text").asString();
                            bloc.put("text", bloc.path("text").asString() + t);
                            sortie.accept(new TexteDelta(t));
                        }
                        case "input_json_delta" -> entrees.get(i).append(d.path("partial_json").asString());
                        case "thinking_delta" -> bloc.put("thinking", bloc.path("thinking").asString()
                                + d.path("thinking").asString());
                        case "signature_delta" -> bloc.put("signature", d.path("signature").asString());
                        default -> { /* citations et autres : sans objet ici */ }
                    }
                }
                case "content_block_stop" -> {
                    int i = e.path("index").asInt();
                    StringBuilder entree = entrees.remove(i);
                    if (entree != null && blocs.containsKey(i)) {
                        blocs.get(i).set("input", entree.isEmpty()
                                ? json.createObjectNode() : json.readTree(entree.toString()));
                    }
                }
                case "message_delta" -> {
                    if (e.path("delta").hasNonNull("stop_reason")) {
                        raison[0] = e.path("delta").path("stop_reason").asString();
                    }
                    if (e.path("usage").has("output_tokens")) jetons[1] = e.path("usage").path("output_tokens").asInt();
                }
                case "error" -> throw new Indisponible(HttpAssistant.messageDeFlux("Anthropic",
                        e.path("error").path("type").asString("")), null);
                default -> { /* ping, message_stop */ }
            }
        });

        // Après un repli en cours de réponse, les blocs de réflexion et d'appel
        // d'outil émis avant la frontière ne se renvoient pas ; le texte, si.
        int frontiere = replis.isEmpty() ? -1 : replis.getLast();
        blocs.forEach((i, bloc) -> {
            String type = bloc.path("type").asString();
            if ("fallback".equals(type)) return;
            if (i < frontiere && !"text".equals(type)) return;
            sortie.accept(new BlocTermine(bloc));
        });
        sortie.accept(new Consommation(jetons[0], jetons[1]));
        sortie.accept(new Fin(raison[0]));
    }
}

package com.labo.anapath.assistant.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * API Chat Completions d'OpenAI, en flux, par HTTP direct.
 *
 * <p>Traduit les blocs neutres : le texte d'un message devient {@code content},
 * un {@code tool_use} devient un {@code tool_calls[]} de l'assistant, un
 * {@code tool_result} un message {@code role:"tool"}. Les blocs propres à
 * Anthropic (réflexion) sont ignorés.</p>
 */
@Component
public class ClientOpenAi implements FournisseurLlm {

    private final RestClient http;
    private final ObjectMapper json;

    public ClientOpenAi(RestClient.Builder builder, ObjectMapper json,
                        @Value("${app.assistant.openai-url:https://api.openai.com}") String url) {
        this.http = HttpAssistant.client(builder, url);
        this.json = json;
    }

    @Override
    public String code() {
        return "OPENAI";
    }

    @Override
    public void completer(Requete r, Consumer<Evenement> sortie) {
        http.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + r.cleApi())
                .body(json.writeValueAsString(corps(r)))
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw HttpAssistant.erreur("OpenAI", res.getStatusCode().value(), res.getBody());
                    }
                    lireFlux(res.getBody(), sortie);
                    return null;
                });
    }

    ObjectNode corps(Requete r) {
        ObjectNode corps = json.createObjectNode();
        corps.put("model", r.modele());
        corps.put("stream", true);
        corps.putObject("stream_options").put("include_usage", true);
        corps.put("max_completion_tokens", r.maxJetons());
        corps.set("messages", messages(r.systeme(), r.messages()));
        if (r.outils() != null && !r.outils().isEmpty()) {
            ArrayNode outils = corps.putArray("tools");
            for (JsonNode o : r.outils()) {
                ObjectNode f = outils.addObject().put("type", "function").putObject("function");
                f.put("name", o.path("name").asString());
                f.put("description", o.path("description").asString());
                f.set("parameters", o.path("input_schema"));
            }
            corps.put("parallel_tool_calls", false);
        }
        return corps;
    }

    ArrayNode messages(String systeme, List<JsonNode> neutres) {
        ArrayNode sortie = json.createArrayNode();
        sortie.addObject().put("role", "system").put("content", systeme);
        for (JsonNode m : neutres) {
            String role = m.path("role").asString();
            StringBuilder texte = new StringBuilder();
            ArrayNode appels = json.createArrayNode();
            for (JsonNode bloc : m.path("content")) {
                switch (bloc.path("type").asString()) {
                    case "text" -> texte.append(bloc.path("text").asString());
                    case "tool_use" -> {
                        ObjectNode a = appels.addObject().put("id", bloc.path("id").asString()).put("type", "function");
                        a.putObject("function").put("name", bloc.path("name").asString())
                                .put("arguments", bloc.path("input").toString());
                    }
                    case "tool_result" -> {
                        JsonNode c = bloc.get("content");
                        sortie.addObject().put("role", "tool")
                                .put("tool_call_id", bloc.path("tool_use_id").asString())
                                .put("content", c == null ? "" : c.isString() ? c.asString() : c.toString());
                    }
                    default -> { /* bloc propre à un autre fournisseur */ }
                }
            }
            if ("tool".equals(role)) continue;
            if (texte.isEmpty() && appels.isEmpty()) continue;
            ObjectNode msg = sortie.addObject().put("role", "assistant".equals(role) ? "assistant" : "user");
            if (texte.isEmpty()) msg.putNull("content"); else msg.put("content", texte.toString());
            if (!appels.isEmpty()) msg.set("tool_calls", appels);
        }
        return sortie;
    }

    void lireFlux(InputStream flux, Consumer<Evenement> sortie) throws IOException {
        StringBuilder texte = new StringBuilder();
        var appels = new TreeMap<Integer, ObjectNode>();
        var arguments = new TreeMap<Integer, StringBuilder>();
        int[] jetons = new int[2];
        String[] fin = {"stop"};

        LecteurSse.lire(flux, (evenement, donnees) -> {
            if ("[DONE]".equals(donnees.strip())) return;
            JsonNode e = json.readTree(donnees);
            if (e.has("error")) {
                JsonNode err = e.path("error");
                throw new Indisponible(HttpAssistant.messageDeFlux("OpenAI",
                        err.path("type").asString(err.path("code").asString(""))), null);
            }
            if (e.hasNonNull("usage")) {
                jetons[0] = e.path("usage").path("prompt_tokens").asInt();
                jetons[1] = e.path("usage").path("completion_tokens").asInt();
            }
            for (JsonNode choix : e.path("choices")) {
                JsonNode d = choix.path("delta");
                if (d.hasNonNull("content")) {
                    String t = d.path("content").asString();
                    texte.append(t);
                    sortie.accept(new TexteDelta(t));
                }
                for (JsonNode a : d.path("tool_calls")) {
                    int i = a.path("index").asInt();
                    ObjectNode appel = appels.computeIfAbsent(i, k -> json.createObjectNode().put("type", "tool_use"));
                    if (a.hasNonNull("id")) appel.put("id", a.path("id").asString());
                    if (a.path("function").hasNonNull("name")) appel.put("name", a.path("function").path("name").asString());
                    arguments.computeIfAbsent(i, k -> new StringBuilder())
                            .append(a.path("function").path("arguments").asString(""));
                }
                if (choix.hasNonNull("finish_reason")) fin[0] = choix.path("finish_reason").asString();
            }
        });

        if (!texte.isEmpty()) sortie.accept(new BlocTermine(json.createObjectNode().put("type", "text").put("text", texte.toString())));
        appels.forEach((i, appel) -> {
            String a = arguments.getOrDefault(i, new StringBuilder()).toString();
            appel.set("input", a.isBlank() ? json.createObjectNode() : json.readTree(a));
            sortie.accept(new BlocTermine(appel));
        });
        sortie.accept(new Consommation(jetons[0], jetons[1]));
        sortie.accept(new Fin(switch (fin[0]) {
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        }));
    }
}

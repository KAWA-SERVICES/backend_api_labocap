package com.labo.anapath.assistant.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Le flux de l'API Messages, rejoué depuis des événements écrits à la main. */
class ClientAnthropicTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ClientAnthropic client = new ClientAnthropic(RestClient.builder(), json, "http://localhost");

    private List<FournisseurLlm.Evenement> lire(String flux) throws Exception {
        List<FournisseurLlm.Evenement> sortie = new ArrayList<>();
        client.lireFlux(new ByteArrayInputStream(flux.getBytes(StandardCharsets.UTF_8)), sortie::add);
        return sortie;
    }

    private static String ev(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    @Test
    @DisplayName("Texte, réflexion et appel d'outil réassemblés ; arguments en plusieurs morceaux")
    void reassemble() throws Exception {
        String flux = ev("message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":120,\"cache_read_input_tokens\":30}}}")
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}")
                + ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig==\"}}")
                + ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + ": ping\n\n"
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Je cherche\"}}")
                + ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\" la file.\"}}")
                + ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"file_priorite\",\"input\":{}}}")
                + ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"lim\"}}")
                + ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ite\\\": 3}\"}}")
                + ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}")
                + ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":45}}")
                + ev("message_stop", "{\"type\":\"message_stop\"}");

        var evenements = lire(flux);

        assertThat(evenements).filteredOn(e -> e instanceof FournisseurLlm.TexteDelta)
                .extracting(e -> ((FournisseurLlm.TexteDelta) e).texte()).containsExactly("Je cherche", " la file.");
        List<JsonNode> blocs = evenements.stream().filter(e -> e instanceof FournisseurLlm.BlocTermine)
                .map(e -> ((FournisseurLlm.BlocTermine) e).bloc()).toList();
        assertThat(blocs).hasSize(3);
        assertThat(blocs.get(0).path("signature").asString()).isEqualTo("sig==");
        assertThat(blocs.get(1).path("text").asString()).isEqualTo("Je cherche la file.");
        assertThat(blocs.get(2).path("input").path("limite").asInt()).isEqualTo(3);
        assertThat(evenements).contains(new FournisseurLlm.Consommation(150, 45), new FournisseurLlm.Fin("tool_use"));
    }

    @Test
    @DisplayName("Repli en cours de réponse : la réflexion d'avant la frontière ne se renvoie pas, le texte si")
    void repli() throws Exception {
        String flux = ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"s\"}}")
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Début \"}}")
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"fallback\"}}")
                + ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"suite\"}}")
                + ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}");

        var types = lire(flux).stream().filter(e -> e instanceof FournisseurLlm.BlocTermine)
                .map(e -> ((FournisseurLlm.BlocTermine) e).bloc().path("type").asString()).toList();
        assertThat(types).containsExactly("text", "text");
    }

    @Test
    @DisplayName("Un événement error interrompt le tour")
    void erreur() {
        assertThatThrownBy(() -> lire(ev("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")))
                .isInstanceOf(FournisseurLlm.Indisponible.class);
    }

    @Test
    @DisplayName("Requête : rôle tool fusionné dans user, pas de temperature, repli sur Opus, aucun sur Haiku")
    void corps() {
        var messages = List.of(
                json.readTree("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Ma file\"}]}"),
                json.readTree("{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"file_resume\",\"input\":{}}]}"),
                json.readTree("{\"role\":\"tool\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":{\"aTraiter\":3}}]}"),
                json.readTree("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Et après ?\"}]}"));
        var corps = client.corps(new FournisseurLlm.Requete("k", "claude-opus-5-5", "consigne", messages,
                json.createArrayNode(), 1000));
        assertThat(corps.has("temperature")).isFalse();
        assertThat(corps.path("fallbacks").asString()).isEqualTo("default");
        JsonNode envoyes = corps.path("messages");
        assertThat(envoyes).hasSize(3);
        assertThat(envoyes.get(2).path("role").asString()).isEqualTo("user");
        assertThat(envoyes.get(2).path("content")).hasSize(2);
        assertThat(envoyes.get(2).path("content").get(0).path("content").asString()).isEqualTo("{\"aTraiter\":3}");

        var haiku = client.corps(new FournisseurLlm.Requete("k", "claude-haiku-5-5", "c", messages, json.createArrayNode(), 1000));
        assertThat(haiku.has("fallbacks")).isFalse();
    }
}

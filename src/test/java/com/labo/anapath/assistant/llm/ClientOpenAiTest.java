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

class ClientOpenAiTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ClientOpenAi client = new ClientOpenAi(RestClient.builder(), json, "http://localhost");

    @Test
    @DisplayName("Flux Chat Completions : texte, appel d'outil en morceaux, consommation, raison normalisée")
    void reassemble() throws Exception {
        String flux = """
                data: {"choices":[{"delta":{"content":"Je "}}]}

                data: {"choices":[{"delta":{"content":"regarde."}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"recette_du_jour","arguments":"{"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":80,"completion_tokens":12}}

                data: [DONE]

                """;
        List<FournisseurLlm.Evenement> sortie = new ArrayList<>();
        client.lireFlux(new ByteArrayInputStream(flux.getBytes(StandardCharsets.UTF_8)), sortie::add);

        List<JsonNode> blocs = sortie.stream().filter(e -> e instanceof FournisseurLlm.BlocTermine)
                .map(e -> ((FournisseurLlm.BlocTermine) e).bloc()).toList();
        assertThat(blocs.get(0).path("text").asString()).isEqualTo("Je regarde.");
        assertThat(blocs.get(1).path("type").asString()).isEqualTo("tool_use");
        assertThat(blocs.get(1).path("name").asString()).isEqualTo("recette_du_jour");
        assertThat(sortie).contains(new FournisseurLlm.Consommation(80, 12), new FournisseurLlm.Fin("tool_use"));
    }

    @Test
    @DisplayName("Traduction : tool_use en tool_calls, tool_result en role tool, réflexion ignorée")
    void messages() {
        var neutres = List.of(
                json.readTree("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Recette ?\"}]}"),
                json.readTree("{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"recette_du_jour\",\"input\":{}}]}"),
                json.readTree("{\"role\":\"tool\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c1\",\"content\":{\"total\":5000}}]}"));
        JsonNode m = client.messages("consigne", neutres);
        assertThat(m).hasSize(4);
        assertThat(m.get(0).path("role").asString()).isEqualTo("system");
        assertThat(m.get(2).path("content").isNull()).isTrue();
        assertThat(m.get(2).path("tool_calls").get(0).path("function").path("arguments").asString()).isEqualTo("{}");
        assertThat(m.get(3).path("role").asString()).isEqualTo("tool");
        assertThat(m.get(3).path("content").asString()).isEqualTo("{\"total\":5000}");
    }
}

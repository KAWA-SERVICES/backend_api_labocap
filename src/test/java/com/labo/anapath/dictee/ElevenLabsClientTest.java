package com.labo.anapath.dictee;

import com.labo.anapath.common.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contrat de {@code POST /v1/speech-to-text} d'ElevenLabs (multipart). */
class ElevenLabsClientTest {

    private static final String BASE = "https://api.eu.residency.elevenlabs.io";

    private MockRestServiceServer serveur;
    private ElevenLabsProperties properties;
    private ElevenLabsClient client;

    @BeforeEach
    void client() {
        RestTemplate restTemplate = new RestTemplate();
        serveur = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new ElevenLabsProperties();
        properties.setBaseUrl(BASE + "/");
        properties.setApiKey("sk_test");
        client = new ElevenLabsClient(restTemplate, properties);
    }

    private static long nbParties(org.springframework.http.client.ClientHttpRequest requete, String nom) throws java.io.IOException {
        String corps = ((MockClientHttpRequest) requete).getBodyAsString();
        return corps.split("name=\"" + nom + "\"", -1).length - 1;
    }

    @Test
    @DisplayName("segment : clé xi-api-key, conservation zéro, modèle médical, français, texte et durée lus")
    void transcrit() {
        properties.setMotsCles(List.of("adénocarcinome", " HER2 ", ""));
        serveur.expect(requestTo(BASE + "/v1/speech-to-text?enable_logging=false"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("xi-api-key", "sk_test"))
                .andExpect(content().multipartDataContains(Map.of(
                        "model_id", "scribe_v2_medical",
                        "language_code", "fra")))
                .andExpect(r -> assertThat(nbParties(r, "keyterms")).isEqualTo(2))
                .andExpect(r -> assertThat(((MockClientHttpRequest) r).getBodyAsString()).contains("HER2\r\n"))
                .andRespond(withSuccess("{\"text\":\"Carcinome canalaire infiltrant.\",\"language_code\":\"fra\","
                        + "\"audio_duration_secs\":4.2,\"words\":[]}", MediaType.APPLICATION_JSON));

        ElevenLabsClient.Transcription t = client.transcrire(new byte[]{1, 2, 3}, "segment.webm");

        assertThat(t.text()).isEqualTo("Carcinome canalaire infiltrant.");
        assertThat(t.audioDurationSecs()).isEqualTo(4.2);
        serveur.verify();
    }

    @Test
    @DisplayName("au-delà de 100 mots-clés, seuls 100 partent (sinon 20 s facturées par segment)")
    void motsClesPlafonnes() {
        properties.setMotsCles(IntStream.range(0, 150).mapToObj(i -> "terme" + i).toList());
        properties.setZeroRetention(false);
        serveur.expect(requestTo(BASE + "/v1/speech-to-text"))
                .andExpect(r -> assertThat(nbParties(r, "keyterms")).isEqualTo(ElevenLabsClient.MAX_MOTS_CLES))
                .andRespond(withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.transcrire(new byte[]{1}, "segment.webm");

        serveur.verify();
    }

    @Test
    @DisplayName("sans conservation zéro, la transcription est effacée chez ElevenLabs ; un échec d'effacement ne prive pas du texte")
    void effaceSansConservationZero() {
        properties.setZeroRetention(false);
        serveur.expect(requestTo(BASE + "/v1/speech-to-text"))
                .andRespond(withSuccess("{\"text\":\"ok\",\"transcription_id\":\"tr_1\"}", MediaType.APPLICATION_JSON));
        serveur.expect(requestTo(BASE + "/v1/speech-to-text/transcripts/tr_1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("xi-api-key", "sk_test"))
                .andRespond(withServerError());

        assertThat(client.transcrire(new byte[]{1}, "segment.webm").text()).isEqualTo("ok");
        serveur.verify();
    }

    @Test
    @DisplayName("erreur ElevenLabs ou clé absente → ExternalApiException (503), sans le détail du fournisseur")
    void indisponible() {
        serveur.expect(requestTo(BASE + "/v1/speech-to-text?enable_logging=false"))
                .andRespond(withServerError().body("{\"detail\":\"interne\"}"));

        assertThatThrownBy(() -> client.transcrire(new byte[]{1}, "segment.webm"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessage("Service de transcription indisponible");

        properties.setApiKey("");
        assertThatThrownBy(() -> client.transcrire(new byte[]{1}, "segment.webm"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("ELEVENLABS_API_KEY");
    }
}

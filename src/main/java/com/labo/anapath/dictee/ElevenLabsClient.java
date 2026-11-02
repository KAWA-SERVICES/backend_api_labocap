package com.labo.anapath.dictee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.labo.anapath.common.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Transcription d'un segment audio par ElevenLabs
 * ({@code POST /v1/speech-to-text}, multipart).
 *
 * <p>Les mots-clés ajoutent 20 % au prix ; au-delà de 100, ElevenLabs facture au moins 20 s par requête :
 * nos segments durent 5 à 30 s, la liste est donc coupée à {@link #MAX_MOTS_CLES}.</p>
 */
@Service
@Slf4j
public class ElevenLabsClient {

    static final int MAX_MOTS_CLES = 100;

    private final RestTemplate restTemplate;
    private final ElevenLabsProperties properties;

    /**
     * Client propre, pas le RestTemplate partagé : son délai de lecture (10 s)
     * coupe une transcription d'une minute de dictée avec mots-clés.
     */
    @Autowired
    public ElevenLabsClient(ElevenLabsProperties properties) {
        this(restTemplateDeTranscription(), properties);
    }

    ElevenLabsClient(RestTemplate restTemplate, ElevenLabsProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    private static RestTemplate restTemplateDeTranscription() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    /** Texte transcrit, durée d'audio facturée et identifiant chez ElevenLabs (les deux derniers peuvent manquer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transcription(String text,
                                @JsonProperty("audio_duration_secs") Double audioDurationSecs,
                                @JsonProperty("transcription_id") String transcriptionId) {
        public Transcription(String text, Double audioDurationSecs) {
            this(text, audioDurationSecs, null);
        }
    }

    public Transcription transcrire(byte[] audio, String nomDeFichier) {
        if (!properties.isUsable()) {
            throw new ExternalApiException("Dictée non configurée (ELEVENLABS_API_KEY)");
        }
        MultiValueMap<String, Object> corps = new LinkedMultiValueMap<>();
        corps.add("model_id", properties.getModelId());
        corps.add("language_code", properties.getLanguageCode());
        corps.add("tag_audio_events", "false");
        corps.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return nomDeFichier;
            }
        });
        List<String> motsCles = properties.getMotsCles().stream().filter(m -> !m.isBlank()).toList();
        if (motsCles.size() > MAX_MOTS_CLES) {
            log.warn("ElevenLabs : {} mots-clés configurés, seuls les {} premiers sont envoyés", motsCles.size(), MAX_MOTS_CLES);
            motsCles = motsCles.subList(0, MAX_MOTS_CLES);
        }
        motsCles.forEach(m -> corps.add("keyterms", m.strip()));

        HttpHeaders headers = new HttpHeaders();
        headers.set("xi-api-key", properties.getApiKey());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String url = base + "/v1/speech-to-text" + (properties.isZeroRetention() ? "?enable_logging=false" : "");
        try {
            Transcription reponse = restTemplate.postForObject(url, new HttpEntity<>(corps, headers), Transcription.class);
            if (reponse == null || reponse.text() == null) {
                throw new ExternalApiException("Transcription : réponse vide");
            }
            if (!properties.isZeroRetention() && reponse.transcriptionId() != null) {
                supprimer(base, reponse.transcriptionId(), headers);
            }
            return reponse;
        } catch (RestClientException e) {
            // Le détail (corps de réponse d'ElevenLabs) reste au journal : ce message remonte au client.
            log.warn("ElevenLabs speech-to-text : {}", e.getMessage());
            throw new ExternalApiException("Service de transcription indisponible", e);
        }
    }

    /**
     * Sans conservation zéro (offre Entreprise), ElevenLabs garde la transcription
     * dans l'historique du compte : on l'efface aussitôt
     * ({@code DELETE /v1/speech-to-text/transcripts/{id}}). Au mieux : un échec
     * est journalisé, le médecin a quand même son texte.
     */
    private void supprimer(String base, String transcriptionId, HttpHeaders headers) {
        HttpHeaders h = new HttpHeaders();
        h.set("xi-api-key", headers.getFirst("xi-api-key"));
        try {
            restTemplate.exchange(base + "/v1/speech-to-text/transcripts/{id}", HttpMethod.DELETE,
                    new HttpEntity<>(h), Void.class, transcriptionId);
        } catch (RestClientException e) {
            log.warn("ElevenLabs : transcription {} non effacée : {}", transcriptionId, e.getMessage());
        }
    }
}

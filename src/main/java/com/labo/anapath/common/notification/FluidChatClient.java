package com.labo.anapath.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.labo.anapath.common.exception.ExternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Appels automatiques par FluidChat : l'appel sort par le téléphone passerelle
 * du laboratoire, fait écouter un audio, et FluidChat relance de lui-même selon
 * les règles données ici.
 *
 * <p>Pas de {@code callback_url} : les callbacks de FluidChat ne sont pas signés
 * et sa documentation impose de toute façon de relire l'appel avant d'agir.
 * {@link #lire} fait ce travail depuis une tâche planifiée, sans ouvrir de route
 * publique.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FluidChatClient {

    /** Tentatives au total : le premier appel et deux relances. */
    static final int MAX_TENTATIVES = 3;
    /** Attente entre deux tentatives, en minutes. */
    static final int DELAI_RELANCE_MINUTES = 60;
    /** Issues qui déclenchent une relance ; un décroché partiel n'est pas relancé. */
    static final List<String> RELANCER_SUR = List.of("no_answer", "busy", "unreachable");
    /** Un bip qui a sonné sans décroché a atteint son but : seul un bip qui n'est pas passé est relancé. */
    static final List<String> RELANCER_BIP_SUR = List.of("busy", "unreachable");

    private final RestTemplate restTemplate;
    private final FluidChatProperties properties;
    /** Lien mp3 → audio FluidChat ({@code custom/<id>}) déjà déposé. */
    // ponytail: cache mémoire, un lien est redéposé une fois par démarrage ; à persister si FluidChat facture le stockage.
    private final Map<String, String> audiosDeposes = new ConcurrentHashMap<>();

    /** Un appel tel que FluidChat le décrit (§4 de son API) ; en file, il n'a encore ni écoute ni tentative. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Appel(
            long id,
            String status,
            String result,
            @JsonProperty("listened_pct") Integer listenedPct,
            Integer attempts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AudioDepose(String audio) {}

    /**
     * Met un appel en file chez FluidChat.
     *
     * @param plage     {@code call_window} : hors de cette plage, l'appel et ses relances attendent
     * @param reference renvoyée telle quelle, visible dans « Voix › Historique » de FluidChat
     */
    public Appel lancer(String to, String audio, Map<String, Object> plage, String reference) {
        Map<String, Object> corps = Map.of(
                "to", to,
                "audio", audioFluidChat(audio),
                "max_attempts", MAX_TENTATIVES,
                "retry_on", RELANCER_SUR,
                "retry_delay_minutes", DELAI_RELANCE_MINUTES,
                "call_window", plage,
                "reference", reference == null ? "" : reference);
        return echanger(HttpMethod.POST, "/v1/api/calls", corps);
    }

    /**
     * Fait sonner le numéro une vingtaine de secondes puis raccroche : le patient
     * voit un appel manqué et peut enregistrer le numéro avant l'appel du résultat.
     *
     * @param audio joué seulement si le patient décroche ; nul : FluidChat raccroche aussitôt
     */
    public Appel bip(String to, String audio, Map<String, Object> plage, String reference) {
        Map<String, Object> corps = new HashMap<>(Map.of(
                "to", to,
                "bip", true,
                "max_attempts", MAX_TENTATIVES,
                "retry_on", RELANCER_BIP_SUR,
                "retry_delay_minutes", DELAI_RELANCE_MINUTES,
                "call_window", plage,
                "reference", reference == null ? "" : reference));
        if (audio != null) {
            corps.put("audio", audioFluidChat(audio));
        }
        return echanger(HttpMethod.POST, "/v1/api/calls", corps);
    }

    /** Relit un appel : statut, issue de la dernière tentative, part écoutée. */
    public Appel lire(long id) {
        return echanger(HttpMethod.GET, "/v1/api/calls/" + id, null);
    }

    /**
     * Audio tel que les Paramètres le donnent : un lien mp3, que FluidChat
     * télécharge une fois ({@code POST /v1/api/audio}), ou directement un
     * {@code custom/<id>} déjà déposé.
     */
    String audioFluidChat(String reglage) {
        if (reglage == null || !(reglage.startsWith("https://") || reglage.startsWith("http://"))) {
            return reglage;
        }
        return audiosDeposes.computeIfAbsent(reglage, lien -> echanger(HttpMethod.POST, "/v1/api/audio",
                Map.of("url", lien), AudioDepose.class).audio());
    }

    private Appel echanger(HttpMethod methode, String chemin, Object corps) {
        return echanger(methode, chemin, corps, Appel.class);
    }

    private <T> T echanger(HttpMethod methode, String chemin, Object corps, Class<T> type) {
        if (!properties.isUsable()) {
            throw new ExternalApiException("FluidChat non configuré (FLUIDCHAT_BASE_URL, FLUIDCHAT_API_KEY)");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            T reponse = restTemplate.exchange(properties.url(chemin), methode,
                    new HttpEntity<>(corps, headers), type).getBody();
            if (reponse == null) {
                throw new ExternalApiException("FluidChat : réponse vide");
            }
            return reponse;
        } catch (RestClientException e) {
            // Le détail (adresse, corps de réponse) reste au journal : ce message
            // remonte tel quel au client.
            log.warn("FluidChat {} {} : {}", methode, chemin, e.getMessage());
            throw new ExternalApiException("Service d'appel indisponible", e);
        }
    }
}

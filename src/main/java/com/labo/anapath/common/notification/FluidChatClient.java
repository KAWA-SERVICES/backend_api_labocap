package com.labo.anapath.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.common.exception.InvalidOperationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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
 *
 * <p>FluidChat est aussi le guichet du crédit prépayé SMS et appels
 * ({@code docs/CREDIT.md} de fluidchat-saas) : envoi des SMS, solde, consommation
 * et recharges Mobile Money. Un crédit épuisé y répond {@code 402 CREDIT}, rendu
 * ici par un message qui dit quoi faire.</p>
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

    /** SMS accepté par FluidChat ; {@code balanceFcfa} est nul si le crédit n'est pas activé. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SmsEnvoye(
            long id,
            String reference,
            int parts,
            @JsonProperty("cost_fcfa") long costFcfa,
            String status,
            @JsonProperty("balance_fcfa") Long balanceFcfa) {}

    /** Portefeuille du laboratoire chez FluidChat ({@code GET /v1/api/wallet}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Portefeuille(
            String plan,
            @JsonProperty("balance_fcfa") Long balanceFcfa,
            @JsonProperty("unlimited_until") String unlimitedUntil,
            boolean low,
            @JsonProperty("low_threshold_fcfa") long lowThresholdFcfa,
            Tarifs prices,
            Mois month,
            @JsonProperty("pending_topup") Recharge pendingTopup) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tarifs(
            @JsonProperty("sms_part_fcfa") long smsPartFcfa,
            @JsonProperty("call_second_fcfa") long callSecondFcfa,
            @JsonProperty("unlimited_month_fcfa") long unlimitedMonthFcfa) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mois(
            long sms,
            @JsonProperty("sms_parts") long smsParts,
            @JsonProperty("sms_fcfa") long smsFcfa,
            @JsonProperty("call_seconds") long callSeconds,
            @JsonProperty("call_fcfa") long callFcfa) {}

    /** Recharge Mobile Money du crédit ; {@code status} : pending, paid, failed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recharge(
            long id,
            @JsonProperty("amount_fcfa") long amountFcfa,
            int months,
            String network,
            String status,
            @JsonProperty("failure_reason") String failureReason,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("paid_at") String paidAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Erreur(Detail error) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Detail(String code, String message) {}
    }

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
     * Envoie un SMS par FluidChat, débité du crédit prépayé.
     *
     * @param reference clé d'idempotence (64 caractères au plus) : un renvoi avec
     *                  la même référence rend l'envoi existant sans second débit
     */
    public SmsEnvoye envoyerSms(String to, String texte, String reference) {
        return echanger(HttpMethod.POST, "/v1/api/sms",
                Map.of("to", to, "text", texte, "reference", reference), SmsEnvoye.class);
    }

    /** Solde, offre, tarifs et consommation du mois. */
    public Portefeuille portefeuille() {
        return echanger(HttpMethod.GET, "/v1/api/wallet", null, Portefeuille.class);
    }

    /** Vingt dernières recharges. */
    public List<Recharge> recharges() {
        return List.of(echanger(HttpMethod.GET, "/v1/api/wallet/topups", null, Recharge[].class));
    }

    /**
     * Lance une collecte Mobile Money : le payeur valide sur son téléphone.
     *
     * @param montantFcfa offre à l'unité, sinon nul
     * @param mois        forfait illimité, sinon nul
     */
    public Recharge recharger(Long montantFcfa, Integer mois, String reseau, String telephone) {
        Map<String, Object> corps = new HashMap<>(Map.of("network", reseau, "phone", telephone));
        if (montantFcfa != null) {
            corps.put("amount_fcfa", montantFcfa);
        }
        if (mois != null) {
            corps.put("months", mois);
        }
        return echanger(HttpMethod.POST, "/v1/api/wallet/topups", corps, Recharge.class);
    }

    /** Relit une recharge ; FluidChat réinterroge Bulkpay si elle est encore en attente. */
    public Recharge recharge(long id) {
        return echanger(HttpMethod.GET, "/v1/api/wallet/topups/" + id, null, Recharge.class);
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
        } catch (RestClientResponseException e) {
            log.warn("FluidChat {} {} : {}", methode, chemin, e.getStatusCode());
            Erreur.Detail detail = detail(e);
            String message = messageDErreur(e, detail);
            // Un refus qui demande une action de l'utilisateur (recharger, attendre,
            // corriger la saisie) n'est pas une panne : 422 et non 503.
            throw refusMetier(e, detail)
                    ? new InvalidOperationException(message)
                    : new ExternalApiException(message, e);
        } catch (RestClientException e) {
            // Le détail (adresse, corps de réponse) reste au journal : ce message
            // remonte tel quel au client.
            log.warn("FluidChat {} {} : {}", methode, chemin, e.getMessage());
            throw new ExternalApiException("Service d'appel indisponible", e);
        }
    }

    /**
     * Message montré à l'utilisateur pour un refus de FluidChat.
     *
     * <p>Le crédit épuisé et la recharge déjà en cours disent quoi faire ; un refus
     * de validation (400, 422 : numéro, montant) reprend le message de FluidChat, rédigé en
     * français pour ses propres clients. Tout le reste reste générique : le corps
     * peut contenir des détails internes.</p>
     */
    private static String messageDErreur(RestClientResponseException e, Erreur.Detail detail) {
        String code = detail == null ? "" : String.valueOf(detail.code());
        if (e.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
            return "CREDIT".equals(code)
                    ? "Crédit SMS et appels épuisé : rechargez-le depuis le tableau de bord."
                    : "Abonnement FluidChat suspendu : contactez KAWA SERVICES.";
        }
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
            return "Une recharge est déjà en cours : validez-la ou attendez son échec.";
        }
        if ((e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_CONTENT.value()
                || e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value())
                && detail != null && detail.message() != null) {
            return "Demande refusée : " + detail.message();
        }
        return "Service d'appel indisponible";
    }

    private static boolean refusMetier(RestClientResponseException e, Erreur.Detail detail) {
        int statut = e.getStatusCode().value();
        return statut == HttpStatus.BAD_REQUEST.value()
                || statut == HttpStatus.CONFLICT.value()
                || statut == HttpStatus.UNPROCESSABLE_CONTENT.value()
                || (statut == HttpStatus.PAYMENT_REQUIRED.value()
                        && detail != null && "CREDIT".equals(detail.code()));
    }

    private static Erreur.Detail detail(RestClientResponseException e) {
        try {
            Erreur erreur = e.getResponseBodyAs(Erreur.class);
            return erreur == null ? null : erreur.error();
        } catch (RuntimeException illisible) {
            return null;
        }
    }
}

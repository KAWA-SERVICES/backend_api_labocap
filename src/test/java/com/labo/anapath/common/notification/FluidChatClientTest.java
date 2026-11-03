package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contrat de FluidChat ({@code docs/API.md}) : dépôt d'un audio par lien, bip. */
class FluidChatClientTest {

    private static final String BASE = "https://demo-fluidchat.kawaservices.com";
    private static final String LIEN = "https://caap.bj/wp-content/uploads/2023/06/RESULTAT-DISPONIBLE-FRANCAIS-VF.mp3";
    private static final Map<String, Object> PLAGE = Map.of("start", "08:00", "end", "18:00");

    private MockRestServiceServer serveur;
    private FluidChatClient client;

    @BeforeEach
    void client() {
        RestTemplate restTemplate = new RestTemplate();
        serveur = MockRestServiceServer.bindTo(restTemplate).build();
        FluidChatProperties properties = new FluidChatProperties();
        properties.setBaseUrl(BASE);
        properties.setApiKey("fc_live_test");
        client = new FluidChatClient(restTemplate, properties);
    }

    private static String appel() {
        return "{\"id\":57,\"status\":\"queued\",\"attempts\":0}";
    }

    @Test
    @DisplayName("audio en lien : déposé une seule fois chez FluidChat, puis son custom/<id> sert à chaque appel")
    void lienDeposeUneFois() {
        serveur.expect(ExpectedCount.once(), requestTo(BASE + "/v1/api/audio"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.url").value(LIEN))
                .andRespond(withSuccess("{\"id\":2,\"audio\":\"custom/2\"}", MediaType.APPLICATION_JSON));
        serveur.expect(ExpectedCount.twice(), requestTo(BASE + "/v1/api/calls"))
                .andExpect(jsonPath("$.audio").value("custom/2"))
                .andRespond(withSuccess(appel(), MediaType.APPLICATION_JSON));

        client.lancer("+22997000001", LIEN, PLAGE, "26-0001");
        client.lancer("+22997000002", LIEN, PLAGE, "26-0002");

        serveur.verify();
    }

    @Test
    @DisplayName("bip : bip=true, audio custom/<id> transmis tel quel, sans audio la clé est absente")
    void bip() {
        serveur.expect(requestTo(BASE + "/v1/api/calls"))
                .andExpect(jsonPath("$.bip").value(true))
                .andExpect(jsonPath("$.audio").value("custom/9"))
                .andExpect(jsonPath("$.retry_on.length()").value(2))
                .andRespond(withSuccess(appel(), MediaType.APPLICATION_JSON));
        serveur.expect(requestTo(BASE + "/v1/api/calls"))
                .andExpect(jsonPath("$.audio").doesNotExist())
                .andRespond(withSuccess(appel(), MediaType.APPLICATION_JSON));

        client.bip("+22997000001", "custom/9", PLAGE, "BIP-FA260001");
        client.bip("+22997000001", null, PLAGE, "BIP-FA260001");

        serveur.verify();
    }

    @Test
    @DisplayName("SMS : POST /v1/api/sms avec la clé, la référence d'idempotence, et lecture du coût")
    void sms() {
        serveur.expect(requestTo(BASE + "/v1/api/sms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer fc_live_test"))
                .andExpect(jsonPath("$.to").value("2290197000001"))
                .andExpect(jsonPath("$.text").value("Votre résultat est prêt"))
                .andExpect(jsonPath("$.reference").value("labocap_report-abc"))
                .andRespond(withSuccess("{\"id\":12,\"reference\":\"labocap_report-abc\",\"parts\":1,"
                        + "\"cost_fcfa\":24,\"status\":\"accepted\",\"balance_fcfa\":4976}", MediaType.APPLICATION_JSON));

        FluidChatClient.SmsEnvoye envoi = client.envoyerSms("2290197000001", "Votre résultat est prêt", "labocap_report-abc");

        assertThat(envoi.costFcfa()).isEqualTo(24);
        assertThat(envoi.balanceFcfa()).isEqualTo(4976L);
        serveur.verify();
    }

    @Test
    @DisplayName("402 CREDIT : message qui dit de recharger depuis le tableau de bord")
    void creditEpuise() {
        serveur.expect(requestTo(BASE + "/v1/api/sms"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"CREDIT\",\"message\":\"crédit insuffisant\"}}"));

        assertThatThrownBy(() -> client.envoyerSms("2290197000001", "t", "ref-000000000000"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessage("Crédit SMS et appels épuisé : rechargez-le depuis le tableau de bord.");
    }

    @Test
    @DisplayName("402 SUBSCRIPTION : abonnement suspendu, distinct du crédit")
    void abonnementSuspendu() {
        serveur.expect(requestTo(BASE + "/v1/api/calls"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"SUBSCRIPTION\",\"message\":\"abonnement suspendu\"}}"));

        assertThatThrownBy(() -> client.lancer("+2290197000001", "custom/2", PLAGE, "26-1"))
                .hasMessageContaining("Abonnement FluidChat suspendu");
    }

    @Test
    @DisplayName("recharge refusée en validation (422) : le motif de FluidChat est repris")
    void rechargeInvalide() {
        serveur.expect(requestTo(BASE + "/v1/api/wallet/topups"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount_fcfa").value(500))
                .andExpect(jsonPath("$.network").value("MTN"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"INVALID\",\"message\":\"montant entre 1 000 et 2 000 000 F\"}}"));

        assertThatThrownBy(() -> client.recharger(500L, null, "MTN", "2290197000001"))
                .hasMessage("Demande refusée : montant entre 1 000 et 2 000 000 F");
    }

    @Test
    @DisplayName("portefeuille : offre, solde, tarifs, consommation et recharge en cours lus depuis le JSON de FluidChat")
    void portefeuille() {
        serveur.expect(requestTo(BASE + "/v1/api/wallet"))
                .andRespond(withSuccess("""
                        {"plan":"unit","balance_fcfa":1500,"unlimited_until":null,"low":true,"low_threshold_fcfa":2000,
                         "prices":{"sms_part_fcfa":24,"call_second_fcfa":3,"unlimited_month_fcfa":44870},
                         "month":{"sms":3,"sms_parts":4,"sms_fcfa":96,"call_seconds":75,"call_fcfa":225},
                         "pending_topup":{"id":3,"amount_fcfa":5000,"months":0,"network":"MTN","status":"pending",
                                          "failure_reason":"","created_at":"2026-11-03T10:00:00Z","paid_at":null}}""",
                        MediaType.APPLICATION_JSON));

        FluidChatClient.Portefeuille p = client.portefeuille();

        assertThat(p.balanceFcfa()).isEqualTo(1500L);
        assertThat(p.low()).isTrue();
        assertThat(p.prices().unlimitedMonthFcfa()).isEqualTo(44870);
        assertThat(p.month().callFcfa()).isEqualTo(225);
        assertThat(p.pendingTopup().status()).isEqualTo("pending");
    }
}

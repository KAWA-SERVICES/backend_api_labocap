package com.labo.anapath.common.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}

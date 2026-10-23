package com.labo.anapath.common.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.labo.anapath.common.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Vérifie que le client respecte le contrat publié de FluidPay
 * ({@code docs/openapi.yaml}, {@code POST /api/v1/sms}).
 */
class FluidPaySmsClientTest {

    private static final String URL = "https://app.fluidpay.link/api/v1/sms";
    private static final String CLE = "fluidpay-test-token";
    /** 32 caractères, comme la clé de chiffrement du tableau de bord. */
    private static final String CLE_CHIFFREMENT = "0123456789abcdef0123456789abcdef";
    private static final UUID SOURCE_ID = UUID.randomUUID();

    private MockRestServiceServer serveur;
    private FluidPaySmsClient client;
    private FluidPaySmsProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        RestTemplate restTemplate = new RestTemplate();
        serveur = MockRestServiceServer.createServer(restTemplate);
        properties = new FluidPaySmsProperties();
        properties.setApiKey(CLE);
        properties.setEncryptionKey(CLE_CHIFFREMENT);
        objectMapper = new ObjectMapper();
        client = new FluidPaySmsClient(restTemplate, properties,
                new FluidPayPayloadCipher(objectMapper), objectMapper);
    }

    private FluidPaySmsMessage message() {
        return new FluidPaySmsMessage("ourvoice", "22997000001", "Votre facture est disponible",
                "labocap_invoice-" + SOURCE_ID.toString().replace("-", ""),
                SOURCE_ID.toString(), SmsSender.SOURCE_FACTURE, "CAAP");
    }

    @Test
    @DisplayName("envoi conforme au contrat : enveloppe messages[], champs obligatoires, Bearer")
    void envoiConformeAuContrat() {
        serveur.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + CLE))
                // Sans User-Agent, Cloudflare répond une page HTML au lieu du JSON.
                .andExpect(header("User-Agent", "LabocapAPI/1.0"))
                // Le corps ne porte que la charge chiffrée : un « messages » en
                // clair serait ignoré par le serveur.
                .andExpect(jsonPath("$.encrypted_data").exists())
                .andExpect(jsonPath("$.messages").doesNotExist())
                .andExpect(clairChiffre(clair -> {
                    assertThat(clair.path("nonce").asText()).isNotBlank();
                    assertThat(clair.path("timestamp").asLong()).isPositive();
                    JsonNode m = clair.path("messages").get(0);
                    assertThat(m.path("provider").asText()).isEqualTo("ourvoice");
                    assertThat(m.path("recipient_phone").asText()).isEqualTo("22997000001");
                    assertThat(m.path("message").asText()).isEqualTo("Votre facture est disponible");
                    assertThat(m.path("source_id").asText()).isEqualTo(SOURCE_ID.toString());
                    assertThat(m.path("source_type").asText()).isEqualTo(SmsSender.SOURCE_FACTURE);
                    assertThat(m.path("sender").asText()).isEqualTo("CAAP");
                }))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"message\":\"SMS dispatch initiated\","
                                + "\"messages_count\":1,\"batch_id\":\"lot-abc-123\"}}",
                        MediaType.APPLICATION_JSON));

        FluidPaySmsResult resultat = client.envoyer(message());

        assertThat(resultat.batchId()).isEqualTo("lot-abc-123");
        assertThat(resultat.duplicate()).isFalse();
        serveur.verify();
    }

    @Test
    @DisplayName("expéditeur absent : le champ est omis, FluidPay applique celui du compte")
    void expediteurAbsent_champOmis() {
        serveur.expect(requestTo(URL))
                .andExpect(clairChiffre(clair ->
                        assertThat(clair.path("messages").get(0).has("sender")).isFalse()))
                .andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        client.envoyer(new FluidPaySmsMessage("ourvoice", "22997000001", "texte",
                "labocap_invoice-0123456789abcdef", SOURCE_ID.toString(),
                SmsSender.SOURCE_FACTURE, null));

        serveur.verify();
    }

    @Test
    @DisplayName("422 doublon → succès marqué, aucune exception : le SMS est déjà parti")
    void doublon_nEstPasUneErreur() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":"
                        + "\"All messages are duplicates of existing active records\"}"));

        FluidPaySmsResult resultat = client.envoyer(message());

        assertThat(resultat.duplicate()).isTrue();
        serveur.verify();
    }

    @Test
    @DisplayName("422 de validation → exception : ce n'est pas un doublon, il faut le savoir")
    void validationRefusee_remonte() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"The recipient_phone field is invalid.\"}"));

        assertThatThrownBy(() -> client.envoyer(message()))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("recipient_phone");
    }

    @Test
    @DisplayName("200 avec success:false → exception : rien n'est parti, ne pas le taire")
    void succesFalseEnDeuxCents_remonte() {
        serveur.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":false,\"message\":\"Quota épuisé\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.envoyer(message()))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("Quota épuisé");
    }

    @Test
    @DisplayName("500 → exception")
    void erreurServeur_remonte() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.envoyer(message()))
                .isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("sans clé API → exception nommant le réglage à renseigner")
    void sansCle_exceptionExplicite() {
        properties.setApiKey(null);

        assertThatThrownBy(() -> client.envoyer(message()))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("app.fluidpay.sms.api-key");
    }

    @Test
    @DisplayName("sans clé de chiffrement → refus, car le serveur refuserait l'envoi en clair")
    void sansCleDeChiffrement_exceptionExplicite() {
        properties.setEncryptionKey(null);

        assertThatThrownBy(() -> client.envoyer(message()))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("encryption-key");
    }

    /**
     * Déchiffre {@code encrypted_data} et confie le clair aux vérifications.
     *
     * <p>Le corps HTTP ne dit plus rien de lisible : sans ce détour, on ne
     * vérifierait que la présence d'une chaîne opaque.</p>
     */
    private RequestMatcher clairChiffre(Consumer<JsonNode> verifications) {
        return requete -> {
            String corps = ((MockClientHttpRequest) requete).getBodyAsString();
            try {
                String encode = objectMapper.readTree(corps).path("encrypted_data").asText();
                byte[] brut = Base64.getDecoder().decode(encode);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(CLE_CHIFFREMENT.getBytes(StandardCharsets.UTF_8), "AES"),
                        new IvParameterSpec(Arrays.copyOfRange(brut, 0, 16)));
                verifications.accept(objectMapper.readTree(
                        c.doFinal(Arrays.copyOfRange(brut, 16, brut.length))));
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError("Déchiffrement du corps impossible : " + e, e);
            }
        };
    }

    @Test
    @DisplayName("barre oblique finale de base-url tolérée")
    void baseUrlAvecBarreObliqueFinale() {
        properties.setBaseUrl("https://app.fluidpay.link/");

        assertThat(properties.urlEnvoi()).isEqualTo(URL);
    }
}

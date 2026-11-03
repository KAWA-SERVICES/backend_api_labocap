package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsSenderTest {

    @Mock private FluidChatClient fluidChatClient;
    @Mock private FluidPaySmsClient fluidPaySmsClient;
    @Mock private OurVoiceClient ourVoiceClient;
    @Mock private SettingAppRepository settingAppRepository;

    private FluidChatProperties fluidChat;
    private FluidPaySmsProperties properties;
    private SmsSender sender;

    private static final String NUMERO = "22997000001";
    private static final UUID SOURCE_ID = UUID.randomUUID();

    @BeforeEach
    void setup() {
        properties = new FluidPaySmsProperties();
        fluidChat = new FluidChatProperties();
        sender = new SmsSender(fluidChat, fluidChatClient, properties, fluidPaySmsClient,
                ourVoiceClient, settingAppRepository);
    }

    private void fluidPayConfigure() {
        properties.setApiKey("fluidpay-token");
        properties.setEncryptionKey("0123456789abcdef0123456789abcdef");
        when(fluidPaySmsClient.envoyer(any())).thenReturn(new FluidPaySmsResult("lot-1", false));
    }

    private void ourVoiceConfigure() {
        SettingApp jeton = new SettingApp();
        jeton.setValue("token-ourvoice");
        SettingApp endpoint = new SettingApp();
        endpoint.setValue("https://api.getourvoice.com/v1/messages");
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of("key_ourvoice")))
                .thenReturn(List.of(jeton));
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of("link_ourvoice_sms")))
                .thenReturn(List.of(endpoint));
    }

    private FluidPaySmsMessage envoyerEtCapturer(String message) {
        sender.envoyer(NUMERO, message, SmsSender.SOURCE_FACTURE, SOURCE_ID);
        ArgumentCaptor<FluidPaySmsMessage> capteur =
                ArgumentCaptor.forClass(FluidPaySmsMessage.class);
        verify(fluidPaySmsClient).envoyer(capteur.capture());
        return capteur.getValue();
    }

    @Test
    @DisplayName("FluidPay configuré → le SMS y part, avec l'opérateur et la clé d'idempotence")
    void fluidPayConfigure_estUtilise() {
        fluidPayConfigure();

        FluidPaySmsMessage envoye = envoyerEtCapturer("Votre facture est disponible");

        assertThat(envoye.provider()).isEqualTo("lafricamobile");
        assertThat(envoye.recipientPhone()).isEqualTo(NUMERO);
        assertThat(envoye.sourceId()).isEqualTo(SOURCE_ID.toString());
        assertThat(envoye.sourceType()).isEqualTo(SmsSender.SOURCE_FACTURE);
        verify(ourVoiceClient, never()).sms(any(), any(), any(), any());
    }

    @Test
    @DisplayName("référence d'envoi dans les bornes du contrat (15 à 50 caractères)")
    void referenceIdDansLesBornesDuContrat() {
        fluidPayConfigure();

        FluidPaySmsMessage envoye = envoyerEtCapturer("texte");

        assertThat(envoye.referenceId()).hasSizeBetween(15, 50);
        assertThat(envoye.referenceId()).startsWith(SmsSender.SOURCE_FACTURE);
    }

    @Test
    @DisplayName("message trop long → tronqué plutôt que rejeté en bloc")
    void messageTropLong_estTronque() {
        fluidPayConfigure();

        FluidPaySmsMessage envoye = envoyerEtCapturer("a".repeat(2000));

        assertThat(envoye.message()).hasSize(FluidPaySmsProperties.LONGUEUR_MAX_MESSAGE);
    }

    @Test
    @DisplayName("expéditeur trop long → tronqué à 11 caractères")
    void expediteurTropLong_estTronque() {
        fluidPayConfigure();
        properties.setSender("CENTRE ADECHINA ANATOMIE");

        FluidPaySmsMessage envoye = envoyerEtCapturer("texte");

        assertThat(envoye.sender()).hasSize(FluidPaySmsProperties.LONGUEUR_MAX_SENDER);
    }

    @Test
    @DisplayName("expéditeur non renseigné → omis, FluidPay applique celui du compte")
    void expediteurNonRenseigne_estOmis() {
        fluidPayConfigure();

        assertThat(envoyerEtCapturer("texte").sender()).isNull();
    }

    @Test
    @DisplayName("FluidPay sans clé → repli sur OurVoice, aucun avis n'est perdu")
    void sansCleFluidPay_replieSurOurVoice() {
        // Sans ce repli, mettre la bascule en service avant d'avoir la clé
        // couperait des avis qui fonctionnaient jusque-là.
        ourVoiceConfigure();

        sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_RESULTAT, SOURCE_ID);

        verify(ourVoiceClient).sms("https://api.getourvoice.com/v1/messages",
                "token-ourvoice", NUMERO, "texte");
        verify(fluidPaySmsClient, never()).envoyer(any());
    }

    @Test
    @DisplayName("FluidPay désactivé malgré une clé → repli sur OurVoice")
    void fluidPayDesactive_replieSurOurVoice() {
        properties.setApiKey("fluidpay-token");
        properties.setEncryptionKey("0123456789abcdef0123456789abcdef");
        properties.setEnabled(false);
        ourVoiceConfigure();

        sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_RESULTAT, SOURCE_ID);

        verify(ourVoiceClient).sms(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("aucun transport configuré → exception nommant les deux réglages")
    void aucunTransport_exceptionExplicite() {
        assertThatThrownBy(() -> sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_FACTURE, SOURCE_ID))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("app.fluidpay.sms.api-key")
                .hasMessageContaining("link_ourvoice_sms");
    }

    @Test
    @DisplayName("refus de FluidPay → remonte, sans repli : le rejouer ne ferait que le répéter")
    void refusDeFluidPay_neReplieuPas() {
        properties.setApiKey("fluidpay-token");
        properties.setEncryptionKey("0123456789abcdef0123456789abcdef");
        when(fluidPaySmsClient.envoyer(any()))
                .thenThrow(new ExternalApiException("Envoi SMS refusé par FluidPay : numéro invalide"));

        assertThatThrownBy(() -> sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_FACTURE, SOURCE_ID))
                .isInstanceOf(ExternalApiException.class);
        verify(ourVoiceClient, never()).sms(any(), any(), any(), any());
    }

    @Test
    @DisplayName("numéro absent → exception, aucun appel à la passerelle")
    void numeroAbsent_exception() {
        assertThatThrownBy(() -> sender.envoyer(null, "texte", SmsSender.SOURCE_FACTURE, SOURCE_ID))
                .isInstanceOf(ExternalApiException.class);
        verify(fluidPaySmsClient, never()).envoyer(any());
    }

    private void fluidChatConfigure(boolean sms) {
        fluidChat.setBaseUrl("https://caap.chat-app.fluidpay.link");
        fluidChat.setApiKey("fc_live_test");
        fluidChat.setSmsEnabled(sms);
    }

    @Test
    @DisplayName("interrupteur allumé : le SMS part par FluidChat, avec une référence stable, pas par FluidPay")
    void fluidChat_quandAllume() {
        fluidChatConfigure(true);
        properties.setApiKey("fluidpay-token");
        properties.setEncryptionKey("0123456789abcdef0123456789abcdef");
        when(fluidChatClient.envoyerSms(anyString(), anyString(), anyString()))
                .thenReturn(new FluidChatClient.SmsEnvoye(7, "r", 1, 24, "accepted", 976L));

        sender.envoyer(NUMERO, "Votre résultat est prêt", SmsSender.SOURCE_RESULTAT, SOURCE_ID);

        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(fluidChatClient).envoyerSms(org.mockito.ArgumentMatchers.eq(NUMERO),
                org.mockito.ArgumentMatchers.eq("Votre résultat est prêt"), reference.capture());
        assertThat(reference.getValue())
                .isEqualTo("labocap_report-" + SOURCE_ID.toString().replace("-", ""));
        verify(fluidPaySmsClient, never()).envoyer(any());
    }

    @Test
    @DisplayName("interrupteur éteint : FluidChat configuré pour les appels ne prend pas les SMS")
    void fluidChat_eteint_resteSurFluidPay() {
        fluidChatConfigure(false);
        fluidPayConfigure();

        sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_FACTURE, SOURCE_ID);

        verify(fluidPaySmsClient).envoyer(any());
        verify(fluidChatClient, never()).envoyerSms(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("crédit épuisé chez FluidChat : l'erreur remonte, sans repli sur FluidPay")
    void fluidChat_creditEpuise_pasDeRepli() {
        fluidChatConfigure(true);
        properties.setApiKey("fluidpay-token");
        properties.setEncryptionKey("0123456789abcdef0123456789abcdef");
        when(fluidChatClient.envoyerSms(anyString(), anyString(), anyString()))
                .thenThrow(new com.labo.anapath.common.exception.InvalidOperationException(
                        "Crédit SMS et appels épuisé : rechargez-le depuis le tableau de bord."));

        assertThatThrownBy(() -> sender.envoyer(NUMERO, "texte", SmsSender.SOURCE_FACTURE, SOURCE_ID))
                .isInstanceOf(com.labo.anapath.common.exception.InvalidOperationException.class)
                .hasMessageContaining("Crédit SMS");
        verify(fluidPaySmsClient, never()).envoyer(any());
    }
}

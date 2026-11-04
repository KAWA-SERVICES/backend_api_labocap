package com.labo.anapath.dashboard;

import com.labo.anapath.common.email.EmailService;
import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.FluidChatClient.Mouvement;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.labo.anapath.dashboard.AutonomieDuCredit.Niveau.BAS;
import static com.labo.anapath.dashboard.AutonomieDuCredit.Niveau.EPUISE;
import static com.labo.anapath.dashboard.AutonomieDuCredit.Niveau.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutonomieDuCreditTest {

    private static final Instant MAINTENANT = Instant.parse("2026-11-04T09:00:00Z");

    @Mock private FluidChatClient fluidChat;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;

    private AutonomieDuCredit autonomie;

    @BeforeEach
    void monter() {
        autonomie = new AutonomieDuCredit(fluidChat, userRepository, emailService);
        User secretaire = new User();
        secretaire.setEmail("secretariat@labo.bj");
        when(userRepository.findAlertesCredit()).thenReturn(List.of(secretaire));
        // 7 000 F débités sur 7 jours : 1 000 F par jour.
        when(fluidChat.mouvements("2026-11")).thenReturn(List.of(
                debit("sms", -4_000, "2026-11-03T10:00:00Z"),
                debit("call", -3_000, "2026-11-01T10:00:00Z"),
                debit("topup", 20_000, "2026-11-02T10:00:00Z"),
                debit("manual", -9_999, "2026-11-02T10:00:00Z")));
        // Le registre est mensuel : début de mois, le mois précédent compte aussi,
        // mais pas au-delà des sept jours.
        when(fluidChat.mouvements("2026-10")).thenReturn(List.of(
                debit("sms", -500, "2026-10-20T10:00:00Z")));
    }

    private static Mouvement debit(String type, long montant, String le) {
        return new Mouvement(1, type, montant, 1, "", 0, le);
    }

    private static FluidChatClient.Portefeuille aLUnite(long solde) {
        return new FluidChatClient.Portefeuille("unit", solde, null, false, 1_000,
                new FluidChatClient.Tarifs(24, 3, 0), null, null);
    }

    @Test
    @DisplayName("1 000 F par jour : 5 000 F = 5 jours, OK ; 2 500 F = 2 jours, bas ; 20 F < un SMS, épuisé")
    void niveaux() {
        AutonomieDuCredit.Etat ok = autonomie.etat(aLUnite(5_000), MAINTENANT);
        assertThat(ok.niveau()).isEqualTo(OK);
        assertThat(ok.consommationJourFcfa()).isEqualTo(1_000);
        assertThat(ok.autonomieJours()).isEqualTo(5);
        assertThat(ok.seuilBasFcfa()).isEqualTo(3_000);

        assertThat(autonomie.etat(aLUnite(2_500), MAINTENANT).niveau()).isEqualTo(BAS);
        assertThat(autonomie.etat(aLUnite(20), MAINTENANT).niveau()).isEqualTo(EPUISE);
    }

    @Test
    @DisplayName("forfait illimité ou aucune consommation récente : jamais bas")
    void jamaisBas() {
        assertThat(autonomie.etat(new FluidChatClient.Portefeuille("unlimited", null, "2026-12-01", false, 0,
                null, null, null), MAINTENANT).niveau()).isEqualTo(OK);

        when(fluidChat.mouvements(anyString())).thenReturn(List.of());
        AutonomieDuCredit.Etat etat = autonomie.etat(aLUnite(100), MAINTENANT);
        assertThat(etat.niveau()).isEqualTo(OK);
        assertThat(etat.autonomieJours()).isNull();
    }

    @Test
    @DisplayName("registre relu au plus une fois par heure")
    void registreEnCache() {
        autonomie.etat(aLUnite(5_000), MAINTENANT);
        autonomie.etat(aLUnite(5_000), MAINTENANT.plus(Duration.ofMinutes(30)));
        verify(fluidChat, times(1)).mouvements("2026-11");

        autonomie.etat(aLUnite(5_000), MAINTENANT.plus(Duration.ofMinutes(61)));
        verify(fluidChat, times(2)).mouvements("2026-11");
    }

    @Test
    @DisplayName("crédit bas : un courriel tout de suite, puis toutes les 8 heures, et tout de suite s'il s'épuise")
    void courriels() {
        when(fluidChat.portefeuille()).thenReturn(aLUnite(2_500));
        autonomie.surveiller(MAINTENANT);
        verify(emailService, times(1)).sendAlerteSupervision(eq(List.of("secretariat@labo.bj")),
                argThat(s -> s.contains("bas : environ 2 jour")), any());

        autonomie.surveiller(MAINTENANT.plus(Duration.ofHours(7)));
        verify(emailService, times(1)).sendAlerteSupervision(any(), any(), any());

        autonomie.surveiller(MAINTENANT.plus(Duration.ofHours(8)));
        verify(emailService, times(2)).sendAlerteSupervision(any(), any(), any());

        when(fluidChat.portefeuille()).thenReturn(aLUnite(10));
        autonomie.surveiller(MAINTENANT.plus(Duration.ofHours(9)));
        verify(emailService).sendAlerteSupervision(any(), eq("[Labo] Crédit SMS et appels épuisé"), any());
    }

    @Test
    @DisplayName("registre illisible : le solde reste servi, sans autonomie")
    void registreIllisible() {
        when(fluidChat.mouvements(anyString())).thenThrow(new ExternalApiException("FluidChat injoignable"));
        AutonomieDuCredit.Etat etat = autonomie.etat(aLUnite(5_000), MAINTENANT);
        assertThat(etat.niveau()).isEqualTo(OK);
        assertThat(etat.autonomieJours()).isNull();
    }

    @Test
    @DisplayName("crédit suffisant ou FluidChat injoignable : aucun courriel")
    void silence() {
        when(fluidChat.portefeuille()).thenReturn(aLUnite(50_000));
        autonomie.surveiller(MAINTENANT);
        when(fluidChat.portefeuille()).thenThrow(new ExternalApiException("FluidChat non configuré"));
        autonomie.surveiller(MAINTENANT);
        verify(emailService, never()).sendAlerteSupervision(any(), any(), any());
    }
}

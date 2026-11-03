package com.labo.anapath.dashboard;

import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.PhoneNumbers;
import com.labo.anapath.common.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Crédit prépayé SMS et appels du laboratoire, tenu par FluidChat
 * ({@code docs/CREDIT.md} de fluidchat-saas) : solde, offre, consommation du mois
 * et recharge par Mobile Money, affichés dans une tuile du tableau de bord.
 *
 * <p><b>Pas de copie locale.</b> FluidChat est la seule source du solde : il
 * débite chaque SMS et chaque seconde d'appel au moment où ils partent. Une copie
 * ici divergerait au premier envoi.</p>
 *
 * <p><b>Pas de cloisonnement par agence.</b> Le crédit est celui du laboratoire
 * entier (une clé FluidChat par installation) ; c'est pourquoi la route exige un
 * droit d'administration, {@code manage-messaging-credit}, et non un simple accès
 * au tableau de bord.</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard/credit-messagerie")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('view-dashboard') and hasAuthority('manage-messaging-credit')")
public class CreditMessagerieController {

    private final FluidChatClient fluidChat;

    public record Prix(long smsPartFcfa, long appelSecondeFcfa, long forfaitMoisFcfa) {}

    public record Mois(long sms, long smsParts, long smsFcfa, long appelSecondes, long appelFcfa) {}

    public record Recharge(long id, long montantFcfa, int mois, String reseau, String statut,
                           String motifEchec, String creeLe, String payeLe) {}

    /** Ligne du registre ; {@code type} : topup, plan, sms, call, refund, manual. */
    public record Mouvement(long id, String type, long montantFcfa, int unites, String note,
                            long soldeApres, String le) {}

    /** {@code plan} : none (non activé), unit (à l'unité) ou unlimited (forfait). */
    public record Credit(String plan, Long soldeFcfa, String illimiteJusquAu, boolean bas,
                         long seuilBasFcfa, Prix prix, Mois mois, Recharge rechargeEnCours) {}

    /** Offre à l'unité : {@code montantFcfa} ; forfait : {@code mois}. Jamais les deux. */
    public record DemandeDeRecharge(
            @Min(1_000) @Max(2_000_000) Long montantFcfa,
            @Min(1) @Max(12) Integer mois,
            @NotBlank @Pattern(regexp = "MTN|MOOV|CELTIIS") String reseau,
            @NotBlank @Size(max = 20) String telephone) {}

    @GetMapping
    public ResponseEntity<ApiResponse<Credit>> credit() {
        FluidChatClient.Portefeuille p = fluidChat.portefeuille();
        FluidChatClient.Tarifs t = p.prices();
        FluidChatClient.Mois m = p.month();
        return ResponseEntity.ok(ApiResponse.success(new Credit(
                p.plan(), p.balanceFcfa(), p.unlimitedUntil(), p.low(), p.lowThresholdFcfa(),
                t == null ? null : new Prix(t.smsPartFcfa(), t.callSecondFcfa(), t.unlimitedMonthFcfa()),
                m == null ? null : new Mois(m.sms(), m.smsParts(), m.smsFcfa(), m.callSeconds(), m.callFcfa()),
                recharge(p.pendingTopup()))));
    }

    @GetMapping("/recharges")
    public ResponseEntity<ApiResponse<List<Recharge>>> recharges() {
        return ResponseEntity.ok(ApiResponse.success(
                fluidChat.recharges().stream().map(CreditMessagerieController::recharge).toList()));
    }

    @PostMapping("/recharges")
    public ResponseEntity<ApiResponse<Recharge>> recharger(@Valid @RequestBody DemandeDeRecharge demande,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        if ((demande.montantFcfa() == null) == (demande.mois() == null)) {
            throw new InvalidOperationException("Indiquez soit un montant, soit un nombre de mois.");
        }
        String telephone = PhoneNumbers.toInternational(demande.telephone());
        if (telephone == null) {
            throw new InvalidOperationException("Numéro Mobile Money invalide.");
        }
        Recharge recharge = recharge(fluidChat.recharger(
                demande.montantFcfa(), demande.mois(), demande.reseau(), telephone));
        // Trace de qui a engagé de l'argent ; le numéro n'y figure pas.
        log.info("Recharge du crédit SMS et appels {} lancée par {} : {} F, {} mois, {}",
                recharge.id(), principal.getId(), recharge.montantFcfa(), recharge.mois(), recharge.reseau());
        return ResponseEntity.ok(ApiResponse.success(recharge));
    }

    /**
     * Historique du crédit pour un mois ({@code AAAA-MM}, mois en cours par
     * défaut) : de quoi comprendre chaque franc débité ou crédité.
     */
    @GetMapping("/mouvements")
    public ResponseEntity<ApiResponse<List<Mouvement>>> mouvements(
            @RequestParam(required = false) String mois) {
        // Validé ici : le mois part dans l'URL de FluidChat.
        if (mois != null && !mois.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new InvalidOperationException("Mois au format AAAA-MM.");
        }
        return ResponseEntity.ok(ApiResponse.success(fluidChat.mouvements(mois).stream()
                .map(m -> new Mouvement(m.id(), m.kind(), m.amountFcfa(), m.units(),
                        m.note() == null ? "" : m.note(), m.balanceAfter(), m.createdAt()))
                .toList()));
    }

    @GetMapping("/recharges/{id}")
    public ResponseEntity<ApiResponse<Recharge>> recharge(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(recharge(fluidChat.recharge(id))));
    }

    private static Recharge recharge(FluidChatClient.Recharge r) {
        return r == null ? null : new Recharge(r.id(), r.amountFcfa(), r.months(), r.network(), r.status(),
                r.failureReason() == null ? "" : r.failureReason(), r.createdAt(), r.paidAt());
    }
}

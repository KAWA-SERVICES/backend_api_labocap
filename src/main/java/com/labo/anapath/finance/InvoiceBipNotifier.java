package com.labo.anapath.finance;

import com.labo.anapath.common.notification.DemandeFluidChat;
import com.labo.anapath.common.notification.DemandesEnAttente;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.FluidChatIndisponibleException;
import com.labo.anapath.common.notification.PhoneNumbers;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.report.ReglagesDeRappel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Bipe le patient à la facture : son téléphone sonne une vingtaine de secondes
 * depuis le numéro qui l'appellera au résultat, puis FluidChat raccroche. Le
 * patient voit un appel manqué, l'enregistre, et ne rejette pas l'appel
 * automatique (voir {@code RattrapageAvisPatientJob}).
 *
 * <p>Même déclencheur et même discipline que {@link InvoiceSmsNotifier} : après
 * le commit, en tâche de fond, sans jamais remonter d'erreur à l'agent. Un bip
 * par facture : {@link Invoice#getBipSentAt()} arrête le second événement. Sans
 * lien audio du bip dans Paramètres, aucun bip ne part.
 * FluidChat attend la plage réglementaire pour faire sonner.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceBipNotifier {

    /** {@code status_invoice} d'une facture d'avoir : rien à annoncer au patient. */
    private static final int STATUT_AVOIR = 1;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceBipRepository invoiceBipRepository;
    private final FluidChatClient fluidChatClient;
    private final ReglagesDeRappel reglagesDeRappel;
    private final DemandesEnAttente demandes;

    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInvoiceValidated(InvoiceValidatedEvent event) {
        try {
            biper(event.invoiceId());
        } catch (RuntimeException e) {
            log.warn("Bip de la facture {} non programmé : {}", event.invoiceId(), e.getMessage());
        }
    }

    /** Renvoie les bips que FluidChat n'a pas reçus, dans la plage d'appel ({@code FLUIDCHAT_RESEND_CRON}). */
    @Scheduled(cron = "${app.report.fluidchat-resend.cron:0 7-57/10 * * * *}", zone = "Africa/Porto-Novo")
    public void renvoyerLesBipsEnAttente() {
        if (reglagesDeRappel.plage().contient(ZonedDateTime.now())) {
            demandes.rejouer(DemandeFluidChat.Type.BIP_FACTURE, this::biper);
        }
    }

    /**
     * Programme le bip d'une facture ; rien si elle n'en a pas besoin.
     *
     * @throws FluidChatIndisponibleException FluidChat n'a pas répondu : le bip est gardé pour renvoi
     */
    void biper(UUID invoiceId) {
        // Global : réagit à un événement, hors requête, donc sans agence courante.
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || invoice.getBipSentAt() != null || invoice.getStatusInvoice() == STATUT_AVOIR) {
            demandes.resoudre(DemandeFluidChat.Type.BIP_FACTURE, invoiceId);
            return;
        }
        Patient patient = patient(invoice);
        String numero = patient == null ? null : PhoneNumbers.toInternational(telephoneDuPatient(patient));
        String audio = reglagesDeRappel.audioBip();
        if (numero == null || audio == null) {
            // Sans lien audio, rien ne part : le bandeau des réglages manquants le signale.
            // Un bip en attente devenu sans objet (numéro ou audio retiré) quitte la file.
            demandes.resoudre(DemandeFluidChat.Type.BIP_FACTURE, invoiceId);
            return;
        }
        String secondaire = PhoneNumbers.secondaire(patient.getTelephone1(), patient.getTelephone2());
        String cle = demandes.cle(DemandeFluidChat.Type.BIP_FACTURE, invoiceId,
                () -> DemandesEnAttente.nouvelleCle("bip"));
        FluidChatClient.Appel bip;
        try {
            bip = fluidChatClient.bip("+" + numero, audio,
                    reglagesDeRappel.plage().versCallWindow(), "BIP-" + invoice.getCode(),
                    invoice.getTestOrder() != null ? invoice.getTestOrder().getCode() : null,
                    secondaire == null ? null : "+" + secondaire, cle);
        } catch (FluidChatIndisponibleException e) {
            demandes.enregistrer(DemandeFluidChat.Type.BIP_FACTURE, invoiceId, invoice.getBranchId(), cle,
                    e.getMessage());
            throw e;
        }
        demandes.resoudre(DemandeFluidChat.Type.BIP_FACTURE, invoiceId);
        invoiceBipRepository.marquerBip(invoice.getId(), LocalDateTime.now());
        log.info("Bip de la facture {} programmé (appel FluidChat {})", invoice.getCode(), bip.id());
    }

    /** Patient de la facture, porté par elle ou par son bon d'examen ; une facture de contrat n'en a pas. */
    private static Patient patient(Invoice invoice) {
        Patient patient = invoice.getPatient();
        if (patient == null && invoice.getTestOrder() != null) {
            patient = invoice.getTestOrder().getPatient();
        }
        return patient;
    }

    private static String telephoneDuPatient(Patient patient) {
        String telephone = patient.getTelephone1();
        return (telephone != null && !telephone.isBlank()) ? telephone : patient.getTelephone2();
    }
}

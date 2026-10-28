package com.labo.anapath.finance;

import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.PhoneNumbers;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.report.ReglagesDeRappel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

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

    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInvoiceValidated(InvoiceValidatedEvent event) {
        // Global : réagit à un événement, hors requête, donc sans agence courante.
        Invoice invoice = invoiceRepository.findById(event.invoiceId()).orElse(null);
        if (invoice == null || invoice.getBipSentAt() != null || invoice.getStatusInvoice() == STATUT_AVOIR) {
            return;
        }
        String numero = PhoneNumbers.toInternational(telephoneDuPatient(invoice));
        String audio = reglagesDeRappel.audioBip();
        if (numero == null || audio == null) {
            // Sans lien audio, rien ne part : le bandeau des réglages manquants le signale.
            return;
        }
        try {
            FluidChatClient.Appel bip = fluidChatClient.bip("+" + numero, audio,
                    reglagesDeRappel.plage().versCallWindow(), "BIP-" + invoice.getCode());
            invoiceBipRepository.marquerBip(invoice.getId(), LocalDateTime.now());
            log.info("Bip de la facture {} programmé (appel FluidChat {})", invoice.getCode(), bip.id());
        } catch (RuntimeException e) {
            log.warn("Bip de la facture {} non programmé : {}", invoice.getCode(), e.getMessage());
        }
    }

    /** Patient de la facture, porté par elle ou par son bon d'examen ; une facture de contrat n'en a pas. */
    private static String telephoneDuPatient(Invoice invoice) {
        Patient patient = invoice.getPatient();
        if (patient == null && invoice.getTestOrder() != null) {
            patient = invoice.getTestOrder().getPatient();
        }
        if (patient == null) {
            return null;
        }
        String telephone = patient.getTelephone1();
        return (telephone != null && !telephone.isBlank()) ? telephone : patient.getTelephone2();
    }
}

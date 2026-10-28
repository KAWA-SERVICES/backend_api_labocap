package com.labo.anapath.finance;

import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.common.notification.PhoneNumbers;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.common.notification.SmsTemplates;
import com.labo.anapath.patient.Patient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Prévient le client par SMS qu'une facture validée est téléchargeable.
 *
 * <p>Déclenché par {@link InvoiceValidatedEvent}, après le commit du geste qui
 * valide la facture — encaissement ou normalisation : le SMS accompagne la
 * facture, il ne la conditionne pas. Toute défaillance — client sans téléphone,
 * passerelle indisponible, clé d'API absente — est journalisée et s'arrête là.
 * Elle ne remonte pas à l'agent qui a encaissé, dont l'opération, elle, a bien
 * abouti.</p>
 *
 * <p><b>Un seul SMS par facture, garanti deux fois.</b> Une facture encaissée
 * puis normalisée déclenche deux fois l'événement ;
 * {@link Invoice#getShareSmsSentAt()} arrête le second envoi. La marque n'est
 * posée qu'après réponse de la passerelle, pour qu'un envoi manqué reste
 * rattrapable au geste suivant — et si l'application redémarrait entre l'envoi
 * et la marque, l'identifiant de la facture sert de clé d'idempotence à
 * FluidPay, qui écarte alors le doublon de son côté.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceSmsNotifier {

    /** {@code status_invoice} d'une facture d'avoir. */
    private static final int STATUT_AVOIR = 1;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceShareLinkService shareLinkService;
    private final SmsTemplates smsTemplates;
    private final SmsSender smsSender;

    /**
     * Envoie le SMS de mise à disposition de la facture.
     *
     * <p>{@code AFTER_COMMIT} : le lien envoyé doit désigner une facture réellement
     * enregistrée. {@code @Async} : l'appel HTTP à la passerelle dure, et l'agent
     * n'a pas à attendre devant son écran qu'un opérateur télécom réponde. La
     * transaction propre ({@code REQUIRES_NEW}) est nécessaire car l'écouteur
     * s'exécute sur un autre fil, une fois celle d'origine close — sans elle, la
     * lecture du patient (chargé paresseusement) et l'écriture du jeton de partage
     * échoueraient.</p>
     */
    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInvoiceValidated(InvoiceValidatedEvent event) {
        // Global : réagit à un événement, hors requête, donc sans agence courante.
        Invoice invoice = invoiceRepository.findWithLockById(event.invoiceId()).orElse(null);
        if (invoice == null) {
            log.warn("SMS facture ignoré : facture {} introuvable", event.invoiceId());
            return;
        }

        if (invoice.getShareSmsSentAt() != null) {
            log.debug("SMS facture {} déjà envoyé le {} : second envoi ignoré",
                    invoice.getCode(), invoice.getShareSmsSentAt());
            return;
        }

        try {
            String destinataire = envoyer(invoice);
            log.info("SMS de facture {} envoyé au {}", invoice.getCode(), destinataire);
        } catch (InvalidOperationException e) {
            log.info("SMS facture {} non envoyé : {}", invoice.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("SMS de facture {} non envoyé : {}", invoice.getCode(), e.getMessage());
        }
    }

    /**
     * Envoi à la demande d'un agent, quand le SMS automatique n'est jamais parti
     * (passerelle en panne au moment de la normalisation, numéro ajouté depuis).
     *
     * <p>Un SMS déjà parti n'est pas renvoyé : le patient le recevrait deux fois
     * et le laboratoire le paierait deux fois.</p>
     *
     * @return le numéro auquel le SMS est parti
     * @throws InvalidOperationException si la facture n'est pas normalisée, est un
     *                                   avoir, a déjà son SMS ou n'a aucun numéro
     * @throws com.labo.anapath.common.exception.ExternalApiException si la passerelle refuse
     *                                   ou ne répond pas
     */
    @Transactional
    public String envoyerALaDemande(UUID invoiceId, UUID branchId) {
        Invoice invoice = invoiceRepository.findWithLockByIdAndBranchId(invoiceId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Facture", invoiceId));
        if (invoice.getNormalizedUrl() == null) {
            throw new InvalidOperationException("Normalisez la facture avant d'envoyer son SMS.");
        }
        if (invoice.getStatusInvoice() == STATUT_AVOIR) {
            throw new InvalidOperationException("Une facture d'avoir n'a pas de SMS.");
        }
        if (invoice.getShareSmsSentAt() != null) {
            throw new InvalidOperationException("Le SMS de cette facture est déjà parti.");
        }
        return envoyer(invoice);
    }

    /**
     * Envoie le SMS et marque la facture.
     *
     * <p>La marque est posée après la réponse de la passerelle, et pas avant :
     * marquer plus tôt ferait passer un envoi échoué pour un envoi réussi, sans
     * rattrapage.</p>
     */
    private String envoyer(Invoice invoice) {
        String destinataire = PhoneNumbers.toInternationalA10Chiffres(telephoneDuClient(invoice));
        if (destinataire == null) {
            throw new InvalidOperationException(
                    "Aucun numéro de téléphone pour ce patient : renseignez-le sur sa fiche.");
        }
        String lien = shareLinkService.urlDeTelechargement(invoice);
        String message = smsTemplates.smsFacture(
                invoice.getBranchId(), invoice.getCode(), codeDeLaDemande(invoice), lien);
        // Clé d'idempotence stable : l'identifiant de la facture. Deux envois pour
        // la même pièce — événement rejoué, double clic — ne produisent qu'un SMS,
        // et qu'une facturation.
        smsSender.envoyer(destinataire, message, SmsSender.SOURCE_FACTURE, invoice.getId());
        invoice.setShareSmsSentAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        return destinataire;
    }

    /**
     * Numéro à joindre pour cette facture.
     *
     * <p>La facture porte parfois directement son patient, parfois seulement le bon
     * d'examen qui le porte. Les factures groupées de contrat n'ont ni l'un ni
     * l'autre : personne n'est joignable, et aucun SMS ne part.</p>
     */
    private String telephoneDuClient(Invoice invoice) {
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

    /** Code de la demande d'examen de la facture, ou nul sans demande. */
    private static String codeDeLaDemande(Invoice invoice) {
        return invoice.getTestOrder() != null ? invoice.getTestOrder().getCode() : null;
    }
}

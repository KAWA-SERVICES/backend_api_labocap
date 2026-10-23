package com.labo.anapath.finance;

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
        Invoice invoice = invoiceRepository.findById(event.invoiceId()).orElse(null);
        if (invoice == null) {
            log.warn("SMS facture ignoré : facture {} introuvable", event.invoiceId());
            return;
        }

        if (invoice.getShareSmsSentAt() != null) {
            log.debug("SMS facture {} déjà envoyé le {} : second envoi ignoré",
                    invoice.getCode(), invoice.getShareSmsSentAt());
            return;
        }

        String destinataire = PhoneNumbers.toInternational(telephoneDuClient(invoice));
        if (destinataire == null) {
            log.info("SMS facture {} non envoyé : aucun numéro de téléphone connu",
                    invoice.getCode());
            return;
        }

        try {
            String lien = shareLinkService.urlDeTelechargement(invoice);
            String message = smsTemplates.smsFacture(
                    invoice.getBranchId(), invoice.getCode(), codeDeLaDemande(invoice), lien);
            // Clé d'idempotence stable : l'identifiant de la facture. Deux
            // événements pour la même pièce ne doivent produire qu'un envoi, et
            // qu'une facturation.
            smsSender.envoyer(destinataire, message, SmsSender.SOURCE_FACTURE, invoice.getId());
            // Après la réponse de la passerelle, et pas avant : marquer plus tôt
            // ferait passer un envoi échoué pour un envoi réussi, sans rattrapage.
            invoice.setShareSmsSentAt(LocalDateTime.now());
            invoiceRepository.save(invoice);
            log.info("SMS de facture {} envoyé au {}", invoice.getCode(), destinataire);
        } catch (RuntimeException e) {
            log.error("SMS de facture {} non envoyé : {}", invoice.getCode(), e.getMessage());
        }
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

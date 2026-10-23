package com.labo.anapath.finance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Téléchargement d'une facture depuis le lien reçu par SMS, <b>sans authentification</b>.
 *
 * <p>À la normalisation MECeF/DGI, le client reçoit un SMS contenant un lien vers
 * cette route. Il n'a pas de compte sur l'application : aucune des routes de
 * {@link InvoiceController}, toutes gardées par {@code view-invoices}, ne pouvait
 * le servir.</p>
 *
 * <p><b>Ce qui tient lieu d'autorisation.</b> Le jeton de l'URL, et lui seul :
 * 24 octets tirés au sort, sans lien déductible avec la facture, assortis d'une
 * expiration. La route ne prend jamais d'identifiant de facture — un UUID deviné
 * ouvrirait sinon la comptabilité du laboratoire.</p>
 *
 * <p><b>Pourquoi des réponses HTML.</b> Ce lien s'ouvre dans le navigateur d'un
 * téléphone. Un jeton périmé y afficherait, en JSON, un objet d'erreur que
 * personne ne sait lire ; une phrase en français indique quoi faire.</p>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PublicInvoiceController {

    private final InvoiceShareLinkService shareLinkService;
    private final InvoicePdfService invoicePdfService;
    private final FluidInvoiceService fluidInvoiceService;
    private final com.labo.anapath.common.supervision.CompteurDAlertes compteurDAlertes;

    /**
     * Sert le PDF de la facture désignée par le jeton.
     *
     * @param token jeton du lien public
     * @return le PDF, ou une page d'explication si le jeton est inconnu ou périmé
     */
    // « /f/ » est l'adresse courte des SMS ; l'ancienne reste servie pour les
    // liens déjà envoyés.
    @GetMapping({"/f/{token}", "/api/v1/public/invoices/{token}"})
    public ResponseEntity<byte[]> telecharger(@PathVariable String token) {
        Optional<Invoice> facture;
        try {
            facture = shareLinkService.resoudre(token);
        } catch (InvoiceShareLinkService.LienExpireException e) {
            compteurDAlertes.lienDeFactureRefuse();
            return page(HttpStatus.GONE,
                    "Lien expiré",
                    "Ce lien de téléchargement n'est plus valable. "
                            + "Contactez le laboratoire pour en recevoir un nouveau.");
        }

        if (facture.isEmpty()) {
            // Volontairement identique à une réponse « rien ici » : distinguer un
            // jeton inexistant d'un jeton révoqué renseignerait qui les essaie.
            compteurDAlertes.lienDeFactureRefuse();
            return page(HttpStatus.NOT_FOUND,
                    "Lien introuvable",
                    "Ce lien de téléchargement n'existe pas. "
                            + "Vérifiez qu'il a été copié en entier depuis le SMS.");
        }

        Invoice invoice = facture.get();
        byte[] pdf;
        try {
            pdf = document(invoice);
        } catch (RuntimeException e) {
            log.error("Facture {} : échec de génération du PDF public : {}",
                    invoice.getId(), e.getMessage());
            return page(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Document indisponible",
                    "La facture n'a pas pu être préparée. Réessayez plus tard.");
        }

        String nomFichier = "Facture-"
                + (invoice.getCode() != null ? invoice.getCode() : invoice.getId()) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nomFichier + "\"")
                // Une pièce comptable nominative n'a rien à faire dans le cache d'un
                // proxy partagé, ni dans celui d'un navigateur prêté.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(pdf);
    }

    /** Page d'explication minimale, lisible sur un téléphone. */
    private ResponseEntity<byte[]> page(HttpStatus statut, String titre, String message) {
        String html = """
                <!doctype html><html lang="fr"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <div style="font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1.5rem;color:#313a46">
                  <h1 style="font-size:1.25rem;margin:0 0 .75rem">%s</h1>
                  <p style="margin:0;color:#6c757d">%s</p>
                </div>
                """.formatted(titre, titre, message);
        return ResponseEntity.status(statut)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * La facture normalisée FluidInvoice quand elle existe — celle qui fait foi,
     * avec le QR fiscal et le QR de la demande —, sinon la facture LabSys.
     *
     * <p>Le SMS part au premier encaissement ou à la normalisation : un lien reçu
     * avant la normalisation sert la facture LabSys, puis la version normalisée
     * dès qu'elle existe. Si FluidInvoice ne répond pas, le patient reçoit quand
     * même sa facture plutôt qu'une page d'erreur.</p>
     */
    private byte[] document(Invoice invoice) {
        if (invoice.getFluidinvoiceId() != null) {
            try {
                return fluidInvoiceService.telechargerDocument(invoice.getId(), invoice.getBranchId());
            } catch (RuntimeException e) {
                log.warn("Facture {} : document normalisé indisponible, facture LabSys servie : {}",
                        invoice.getId(), e.getMessage());
            }
        }
        return invoicePdfService.generatePdf(invoice.getId(), invoice.getBranchId());
    }
}

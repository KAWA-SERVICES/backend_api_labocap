package com.labo.anapath.finance;

import java.util.UUID;

/**
 * Une facture vient d'être normalisée : elle est due au client, et téléchargeable.
 *
 * <p>Deux gestes la normalisent, et chacun publie cet événement :</p>
 * <ul>
 *   <li>la normalisation MECeF/DGI par machine e-MECeF
 *       ({@code POST /invoices/{id}/confirm-mecef}) ;</li>
 *   <li>la normalisation par FluidInvoice ({@code POST /invoices/{id}/normalize}).</li>
 * </ul>
 *
 * <p>L'encaissement ne le publie plus : le SMS livre la facture normalisée, et
 * partir avant la normalisation enverrait un lien vers un document qui n'est pas
 * encore la pièce fiscale. {@link InvoiceSmsNotifier} garantit toujours un
 * unique SMS par facture, en s'appuyant sur {@link Invoice#getShareSmsSentAt()}.</p>
 *
 * <p><b>Pourquoi un événement et non un appel direct.</b> L'encaissement engage
 * la caisse et la normalisation est un acte fiscal irréversible ; le SMS n'est
 * qu'une commodité. Les enchaîner dans la même transaction ferait annuler une
 * normalisation pourtant déclarée parce que la passerelle SMS était injoignable. L'écouteur ne
 * s'exécute qu'après commit.</p>
 *
 * @param invoiceId identifiant de la facture validée
 */
public record InvoiceValidatedEvent(UUID invoiceId) {
}

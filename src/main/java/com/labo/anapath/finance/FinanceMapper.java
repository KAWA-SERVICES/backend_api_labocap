package com.labo.anapath.finance;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FinanceMapper {

    @Mapping(target = "code", source = "code")
    @Mapping(target = "paid", source = "paid")
    @Mapping(target = "testOrderId", source = "testOrder.id")
    @Mapping(target = "testOrderCode", source = "testOrder.code")
    @Mapping(target = "patientId", source = "patient.id")
    // « Prénom Nom », dans cet ordre : c'est le format déjà figé sur
    // `clientName` pour l'immense majorité des factures existantes (données
    // migrées depuis Laravel). S'en écarter fait diverger la comparaison
    // clientName/patientName sur presque toutes les factures — vérifié en
    // base : ~96 % des factures suivent ce format, contre une poignée
    // « Nom Prénom ».
    @Mapping(target = "patientName", expression = "java(invoice.getPatient() != null ? com.labo.anapath.common.NomComplet.de(invoice.getPatient().getFirstname(), invoice.getPatient().getLastname()) : null)")
    @Mapping(target = "patientCode", source = "patient.code")
    @Mapping(target = "patientAddress", source = "patient.adresse")
    @Mapping(target = "contratId", source = "contrat.id")
    @Mapping(target = "contratName", source = "contrat.name")
    @Mapping(target = "clientName", source = "clientName")
    @Mapping(target = "clientAddress", source = "clientAddress")
    // « Contact client » du reçu Laravel : à défaut de colonne telephone sur la
    // facture (absente de la base migrée), on reprend le téléphone du patient lié.
    @Mapping(target = "clientContact", expression = "java(invoice.getPatient() != null ? "
            + "java.util.stream.Stream.of(invoice.getPatient().getTelephone1(), invoice.getPatient().getTelephone2())"
            + ".filter(t -> t != null && !t.isBlank()).reduce((a, b) -> a + \" \" + b).orElse(null) : null)")
    @Mapping(target = "date", source = "date")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "statusInvoice", source = "statusInvoice")
    @Mapping(target = "payment", source = "payment")
    @Mapping(target = "codeMecef", source = "codeMecef")
    @Mapping(target = "codeNormalise", source = "codeNormalise")
    @Mapping(target = "qrcode", source = "qrcode")
    @Mapping(target = "referenceCode", source = "reference.code")
    @Mapping(target = "refund", ignore = true)
    @Mapping(target = "details", source = "details")
    InvoiceResponseDto toInvoiceResponseDto(Invoice invoice);

    /**
     * Recopie une réponse facture en y greffant les données de remboursement.
     * Nécessaire car {@link InvoiceResponseDto} est un record immuable et que le
     * remboursement provient d'un autre agrégat que {@link Invoice}.
     */
    default InvoiceResponseDto withRefund(InvoiceResponseDto dto, InvoiceRefundDto refund) {
        return new InvoiceResponseDto(
                dto.id(), dto.code(), dto.testOrderId(), dto.testOrderCode(),
                dto.patientId(), dto.patientName(), dto.patientCode(), dto.patientAddress(),
                dto.contratId(), dto.contratName(),
                dto.clientName(), dto.clientAddress(), dto.clientContact(),
                dto.clientIfu(), dto.facturationFigee(), dto.date(), dto.subtotal(),
                dto.total(), dto.paid(), dto.status(), dto.statusInvoice(), dto.payment(),
                dto.codeMecef(), dto.codeNormalise(), dto.normalizedUrl(), dto.shareSmsSentAt(), dto.qrcode(), dto.referenceCode(),
                refund,
                dto.dueDate(), dto.branchId(), dto.createdAt(), dto.details()
        );
    }

    /**
     * Remplace le QR code du DTO. Réservé au détail d'une facture : le QR est
     * généré à la volée (il n'est pas stocké) et pèse trop pour une liste.
     */
    default InvoiceResponseDto withQrcode(InvoiceResponseDto dto, String qrcode) {
        return new InvoiceResponseDto(
                dto.id(), dto.code(), dto.testOrderId(), dto.testOrderCode(),
                dto.patientId(), dto.patientName(), dto.patientCode(), dto.patientAddress(),
                dto.contratId(), dto.contratName(),
                dto.clientName(), dto.clientAddress(), dto.clientContact(),
                dto.clientIfu(), dto.facturationFigee(), dto.date(), dto.subtotal(),
                dto.total(), dto.paid(), dto.status(), dto.statusInvoice(), dto.payment(),
                dto.codeMecef(), dto.codeNormalise(), dto.normalizedUrl(), dto.shareSmsSentAt(), qrcode, dto.referenceCode(),
                dto.refund(),
                dto.dueDate(), dto.branchId(), dto.createdAt(), dto.details()
        );
    }

    @Mapping(target = "labTestId", source = "labTest.id")
    @Mapping(target = "testName", source = "testName")
    InvoiceDetailDto toInvoiceDetailDto(InvoiceDetail detail);

    @Mapping(target = "invoiceId", source = "invoice.id")
    PaymentResponseDto toPaymentResponseDto(Payment payment);
}

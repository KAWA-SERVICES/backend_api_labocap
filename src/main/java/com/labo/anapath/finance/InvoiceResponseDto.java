package com.labo.anapath.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record InvoiceResponseDto(
        UUID id,
        String code,
        UUID testOrderId,
        String testOrderCode,
        UUID patientId,
        String patientName,
        String patientCode,
        String patientAddress,
        UUID contratId,
        String contratName,
        String clientName,
        String clientAddress,
        String clientContact,
        /** IFU de l'acheteur, tel qu'il part à la DGI. Vide pour un patient. */
        String clientIfu,
        /** L'identité de facturation a été saisie à la main et ne sera plus réécrite. */
        boolean facturationFigee,
        LocalDate date,
        Double subtotal,
        BigDecimal total,
        Boolean paid,
        InvoiceStatus status,
        int statusInvoice,
        String payment,
        String codeMecef,
        String codeNormalise,
        /** Lien FluidInvoice du document normalisé. Non nul ⇒ facture normalisée. */
        String normalizedUrl,
        /** Date d'envoi du SMS de la facture au client. Nul ⇒ aucun SMS n'est parti. */
        LocalDateTime shareSmsSentAt,
        String qrcode,
        String referenceCode,
        InvoiceRefundDto refund,
        LocalDate dueDate,
        UUID branchId,
        LocalDateTime createdAt,
        List<InvoiceDetailDto> details
) {}

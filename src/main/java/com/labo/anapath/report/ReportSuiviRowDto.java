package com.labo.anapath.report;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO d'une ligne du tableau "Suivi des demandes".
 * Contient toutes les infos nécessaires pour les 6 colonnes du tableau Laravel.
 */
public record ReportSuiviRowDto(
        UUID reportId,
        UUID testOrderId,
        String testOrderCode,
        String typeOrderTitle,
        String patientFirstname,
        String patientLastname,
        String patientPhone,
        Boolean isUrgent,
        LocalDateTime createdAt,
        ReportStatus reportStatus,
        boolean hasMacro,
        UUID assignedDoctorId,
        String assignedDoctorName,
        boolean isCalled,
        boolean isDelivered,
        String retrieverName,
        String retrieverSignature,
        LocalDateTime deliveryDate,
        /** Issue du dernier rappel automatique ; nulle tant qu'aucun rappel n'est parti. */
        IssueRappel rappelIssue,
        Integer rappelTentatives,
        Integer rappelPctEcoute,
        LocalDateTime rappelDate
) {}

package com.labo.anapath.report;

import java.time.LocalDateTime;

/**
 * Projection Spring Data pour les requêtes natives du tableau suivi.
 */
public interface ReportSuiviProjection {
    String getReportId();
    String getTestOrderId();
    String getTestOrderCode();
    String getTypeOrderTitle();
    String getPatientFirstname();
    String getPatientLastname();
    String getPatientPhone();
    Boolean getIsUrgent();
    LocalDateTime getCreatedAt();
    String getReportStatus();
    Boolean getHasMacro();
    String getAssignedDoctorId();
    String getAssignedDoctorName();
    Boolean getIsCalled();
    Boolean getIsDelivered();
    String getRetrieverName();
    String getRetrieverSignature();
    LocalDateTime getDeliveryDate();
    String getRappelIssue();
    Integer getRappelTentatives();
    Integer getRappelPctEcoute();
    LocalDateTime getRappelDate();
}

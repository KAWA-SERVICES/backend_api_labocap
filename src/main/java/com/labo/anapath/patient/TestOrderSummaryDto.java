package com.labo.anapath.patient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO de résumé d'une demande d'examen (TestOrder), utilisé dans le profil patient
 * ({@link PatientProfileDto}).
 * <p>
 * Ne contient que les informations essentielles pour afficher l'historique des
 * examens d'un patient sans charger les détails complets de chaque demande
 * (prélèvements, résultats, etc.).
 * </p>
 *
 * @param id               identifiant unique de la demande d'examen
 * @param code             code de référence de la demande
 * @param status           statut de la demande (ex. : "PENDING", "VALIDATED", "DELIVERED")
 * @param prelevementDate  date à laquelle le prélèvement a été réalisé
 * @param createdAt        date et heure de création de la demande
 * @param typeExamen       le type de demande (« Biopsie », « Pièce opératoire »…), ou {@code null}
 */
public record TestOrderSummaryDto(
        UUID id,
        String code,
        String status,
        LocalDate prelevementDate,
        LocalDateTime createdAt,
        String typeExamen
) {}

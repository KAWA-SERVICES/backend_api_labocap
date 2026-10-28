package com.labo.anapath.report;

import java.util.UUID;

/**
 * Un compte-rendu vient de passer à l'état validé : le résultat est disponible
 * au retrait pour le patient.
 *
 * <p>Publié par {@code ReportServiceImpl} sur les trois chemins qui posent ce
 * statut — création directe en validé, enregistrement du formulaire, et l'action
 * dédiée {@code POST /reports/{id}/validate}.</p>
 *
 * <p><b>Aucun écouteur aujourd'hui.</b> Le patient était appelé dès la
 * validation ; il l'est désormais X heures après, par
 * {@link RattrapageAvisPatientJob}, qui lit les comptes rendus validés plutôt
 * que cet événement. Celui-ci reste le point d'accroche d'une réaction
 * immédiate à la validation, hors de sa transaction.</p>
 *
 * @param reportId identifiant du compte-rendu validé
 * @param userId   utilisateur ayant validé, pour la journalisation de l'envoi
 */
public record ReportValidatedEvent(UUID reportId, UUID userId) {
}

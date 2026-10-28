package com.labo.anapath.report;

import java.util.UUID;

/**
 * Résultat de l'avis « résultat disponible » donné au patient.
 *
 * @param channel  {@code CALL} si l'appel est programmé (le SMS part avec, sauf
 *                 échec dit dans {@code message}), {@code SMS} si seul le SMS est
 *                 parti, {@code NONE} si rien n'a pu partir (patient sans numéro)
 * @param reportId identifiant du compte rendu concerné
 * @param appelId  identifiant de l'appel FluidChat, seulement si {@code channel = CALL}
 * @param message  libellé prêt à afficher expliquant l'issue
 */
public record NotifyResponseDto(
        String channel,
        UUID reportId,
        String appelId,
        String message) {
}

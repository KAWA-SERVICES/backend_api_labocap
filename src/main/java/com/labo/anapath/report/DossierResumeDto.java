package com.labo.anapath.report;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * L'état d'un dossier, sans son contenu médical.
 *
 * <h2>Pourquoi ce DTO existe à côté de {@link ReportDetailDto}</h2>
 *
 * <p>Le technicien qui constitue une affectation, ou qui vérifie où en est une
 * demande, a besoin de savoir de quel patient il s'agit, si le compte rendu est
 * terminé et si le résultat est parti. Il n'a pas à lire le diagnostic — c'est
 * une donnée de santé, et la lui montrer parce qu'elle voyageait dans le même
 * objet serait une divulgation par commodité technique.</p>
 *
 * <p>Ce résumé porte donc exactement les champs que l'application mobile lit
 * déjà, aux mêmes noms, moins {@code content} et {@code contentMicro}. Les
 * écrans n'ont rien à adapter : ils reçoivent la même forme, amputée de ce
 * qu'ils n'affichaient pas.</p>
 *
 * <h2>Pourquoi il est servi sous les demandes et non sous les comptes rendus</h2>
 *
 * <p>{@code /reports/by-code} exige {@code view-reports}, que le laborantin n'a
 * pas et ne doit pas avoir. Chercher une demande d'examen relève de
 * {@code view-test-orders} : c'est le droit qui correspond au geste.</p>
 */
public record DossierResumeDto(
        UUID id,
        String code,
        UUID testOrderId,
        String testOrderCode,
        String patientName,
        String titleName,
        ReportStatus status,
        boolean isDelivered,
        String retrieverName,
        String retrieverRelation,
        String retrieverSignature,
        LocalDateTime demandeCreatedAt,
        LocalDateTime deliveryDate,
        /** À qui la demande a été affectée, et quand. Nuls avant l'affectation. */
        String assignedToName,
        String assignmentCode,
        java.time.LocalDate assignmentDate,
        /** Les étiquettes portées sur les contenants de cette demande. */
        java.util.List<String> assignmentLabels,
        /** La note propre à cette demande. */
        String assignmentNote,
        /** La note générale du lot, partagée par toutes ses demandes. */
        String assignmentLotNote,
        /**
         * Les médecins consultés pour avis, nom de famille en tête. Vide quand
         * il n'y en a pas. C'est par ce résumé que l'application mobile ouvre la
         * fiche d'une demande : sans lui, l'avis ne s'y voyait pas.
         */
        java.util.List<String> medecinsPourAvis,
        /** Les mêmes, avec la date de leur lot. */
        java.util.List<AvisDto> avis
) {
}

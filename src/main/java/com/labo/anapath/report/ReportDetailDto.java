package com.labo.anapath.report;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReportDetailDto(
        UUID id,
        String code,
        UUID testOrderId,
        String testOrderCode,
        String patientName,
        UUID titleId,
        String titleName,
        String content,
        String contentMicro,
        String comment,
        String commentSup,
        String descriptionSupplementaire,
        String descriptionSupplementaireMicro,
        ReportStatus status,
        boolean isDelivered,
        boolean isCalled,
        String receiverName,
        /**
         * Nom de la personne ayant emporté le compte-rendu, tel que le pavé de
         * signature l'enregistre. Distinct de {@code receiverName}, alimenté par
         * la fiche : c'est celui-ci que la remise au comptoir écrit, et donc
         * celui qu'il faut montrer pour dire qui est reparti avec le dossier.
         */
        String retrieverName,
        /**
         * À quel titre le compte rendu a été emporté. Nul pour les remises
         * antérieures à la colonne, et pour celles où l'agent n'a rien saisi.
         */
        String retrieverRelation,
        /**
         * Image PNG de la signature recueillie à la remise, en base64 préfixé
         * de son type. Elle était enregistrée sans jamais être relue : sans
         * elle, la preuve de remise existait en base sans être consultable.
         */
        String retrieverSignature,
        /**
         * Date d'enregistrement de la demande d'examen — le premier jalon du
         * suivi d'un dossier, antérieur au compte rendu lui-même.
         */
        LocalDateTime demandeCreatedAt,
        LocalDateTime signatureDate,
        LocalDateTime deliveryDate,
        LocalDateTime callDate,
        UUID signatory1Id,
        String signatory1Name,
        UUID signatory2Id,
        String signatory2Name,
        UUID signatory3Id,
        String signatory3Name,
        UUID reviewedById,
        String reviewedByName,
        List<String> tagNames,
        List<UUID> tagIds,
        List<LogReportDto> logs,
        /**
         * À qui la demande a été affectée, et quand — nuls tant qu'elle ne l'a
         * pas été.
         *
         * <p>Ce jalon manquait au suivi : entre l'enregistrement et le retrait,
         * rien ne disait chez qui la demande était partie. C'est pourtant la
         * question qu'on pose quand un dossier tarde.</p>
         */
        String assignedToName,
        String assignmentCode,
        java.time.LocalDate assignmentDate,
        /**
         * Les étiquettes portées sur les contenants de cette demande.
         *
         * <p>Vide tant que la demande n'est pas affectée, ou si le technicien
         * n'en a posé aucune — elles restent facultatives.</p>
         */
        List<String> assignmentLabels,
        /** La note propre à cette demande, écrite au moment de l'affecter. */
        String assignmentNote,
        /**
         * La note générale du lot, partagée par toutes ses demandes.
         *
         * <p>Distincte de la précédente : « série de garde du samedi » vaut
         * pour le lot entier, « urgent, à traiter avant midi » pour un seul
         * dossier. Les confondre ferait porter à une demande une consigne qui
         * ne la vise pas.</p>
         */
        String assignmentLotNote,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /**
         * Discipline du compte-rendu. Le détail est partagé entre les deux
         * disciplines — le comptoir lit les deux — et l'écran en déduit quoi
         * afficher : texte rédigé ou résultats chiffrés.
         */
        com.labo.anapath.common.Discipline discipline,
        /**
         * Qui a posé l'acte de validation, et sous quel métier.
         *
         * <p>Le métier accompagne le nom parce que c'est lui qui porte le sens :
         * « validé par Florence Hounnou » ne dit pas au lecteur si le
         * compte-rendu a reçu un jugement médical. Nuls sur les comptes-rendus
         * validés avant l'ouverture de la validation au secrétariat.</p>
         */
        UUID validatedById,
        String validatedByName,
        String validatedByRole,
        /**
         * Les médecins à qui la demande a été confiée pour avis, nom de
         * famille en tête. Vide quand il n'y en a pas ; le titulaire, lui, est
         * {@code assignedToName}.
         */
        List<String> medecinsPourAvis
) {
    public record LogReportDto(String action, String description, String userName, LocalDateTime createdAt) {}
}

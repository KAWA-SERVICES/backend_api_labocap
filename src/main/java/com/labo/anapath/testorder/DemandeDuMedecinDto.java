package com.labo.anapath.testorder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Une ligne de la file de travail d'un médecin.
 *
 * <p>Elle porte trois informations que la maquette distingue soigneusement, et
 * qu'il ne faut pas mélanger :</p>
 *
 * <ul>
 *   <li>{@code labels} — les étiquettes du prélèvement, propres à cette
 *       demande ;</li>
 *   <li>{@code note} — la consigne écrite pour cette demande ;</li>
 *   <li>{@code assignmentNote} — celle du lot entier, partagée par toutes ses
 *       demandes, et donc jamais répétée en clair sur chaque ligne.</li>
 * </ul>
 *
 * @param detailId       la ligne d'affectation, seule chose qu'on puisse
 *                       désigner pour changer son statut
 * @param testOrderId    la demande, pour ouvrir sa fiche
 * @param code           le code de la demande, ce que le médecin lit
 * @param patientName    nom puis prénoms, comme partout
 * @param docteurStatus  où en est le médecin — jamais le statut du compte rendu
 * @param reportStatus   où en est le document, qui avance à son propre rythme
 * @param assignmentCode le lot d'où vient cette demande
 * @param assignmentDate la date du lot, qui donne son rang dans la file
 */
public record DemandeDuMedecinDto(
        UUID detailId,
        UUID testOrderId,
        String code,
        String patientName,
        String docteurStatus,

        /**
         * L'état de la demande — PENDING, VALIDATED, DELIVERED, CANCELLED.
         *
         * <p>Le champ s'appelait {@code reportStatus} et portait déjà ceci : un
         * nom qui désignait autre chose que son contenu, et sur lequel toute
         * lecture ultérieure se serait trompée.</p>
         */
        String statutDemande,

        /**
         * L'état du compte rendu — DRAFT, PENDING_REVIEW, VALIDATED, DELIVERED.
         *
         * <p>Nul quand aucun compte rendu n'existe encore. C'est une autre
         * chose que {@code statutDemande} : une demande validée ouvre un compte
         * rendu à l'état de brouillon, elle ne le termine pas.</p>
         */
        String compteRenduStatut,

        /**
         * Le dossier a dépassé le délai sans compte rendu validé.
         *
         * <p>Calculé ici et non à l'écran, avec le seuil qui déclenche déjà
         * l'alerte par courriel ({@code app.alerts.report.days}). Deux
         * définitions du retard — une pour l'écran, une pour le courriel —
         * finiraient par se contredire, et c'est le médecin qui arbitrerait.</p>
         *
         * <p>À ne pas confondre avec {@link #urgent} : celui-ci se constate,
         * celui-là se décide.</p>
         */
        boolean enRetard,

        /**
         * Le bon a été marqué urgent, et n'est pas encore remis.
         *
         * <p>C'est une décision prise à l'accueil — un cas qu'on veut voir
         * passer devant. Elle cesse de valoir une fois le résultat remis :
         * l'urgence portait sur le délai, et ce délai est tenu.</p>
         *
         * <p>Distinct du retard, qui lui se constate après coup. Un dossier
         * peut être l'un, l'autre, les deux, ou aucun.</p>
         */
        boolean urgent,

        /**
         * Où en est le travail du médecin — a_traiter, en_relecture, termine.
         *
         * <p>Déduit du compte rendu, comme les compteurs de l'accueil. Le champ
         * {@code docteurStatus} juste au-dessus dit autre chose : un suivi
         * qu'on coche à la main depuis le web, et que personne ne coche. Un
         * médecin avait 172 dossiers marqués « à traiter » dont 165 étaient
         * finis.</p>
         */
        String avancement,

        /**
         * L'année d'ouverture du dossier.
         *
         * <p>Envoyée plutôt que déduite du code : le code porte bien l'année,
         * mais il n'est attribué qu'à la validation, et une demande encore en
         * attente n'en a pas. Le téléphone s'en sert pour ne compter dans ses
         * alertes que les dossiers de l'année — une déduction faite sur place
         * se serait trompée sur ceux qui n'ont pas encore de nom.</p>
         */
        Integer annee,
        List<String> labels,
        String note,
        String assignmentCode,
        LocalDate assignmentDate,
        String assignmentNote,
        /** Vraie quand le dossier a été confié à ce médecin pour avis. */
        boolean pourAvis) {
}

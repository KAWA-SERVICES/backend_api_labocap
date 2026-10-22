package com.labo.anapath.testorder;

import com.labo.anapath.common.Discipline;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO de filtrage pour la liste paginée des bons d'examen.
 *
 * <p>Tous les champs sont optionnels. Les critères non renseignés (null) sont ignorés
 * par {@link TestOrderSpecification#filter(java.util.UUID, TestOrderFilterDto)}.
 * La recherche textuelle ({@code search}) porte sur le code du bon.
 */
@Getter
@Setter
public class TestOrderFilterDto {

    /** Filtre sur le statut du bon (PENDING, VALIDATED, DELIVERED, CANCELLED). */
    private TestOrderStatus status;

    /**
     * Filtre par statut du COMPTE RENDU : "NONE" (pas de compte rendu),
     * "DRAFT" (en attente), "VALIDATED" (validé), "DELIVERED" (livré).
     */
    private String reportStatus;

    /** Filtre sur l'identifiant du patient. */
    private UUID patientId;

    /** Filtre sur l'identifiant du médecin prescripteur. */
    private UUID doctorId;

    /** Filtre sur le pathologiste attribué (attribuate_doctor_id). */
    private UUID attribuateDoctorId;

    /** Filtre sur l'identifiant de l'hôpital d'origine. */
    private UUID hospitalId;

    /** Filtre sur l'urgence du bon. */
    private Boolean isUrgent;

    /**
     * L'année d'ouverture du dossier — 2026 pour un dossier « 26-0155 ».
     *
     * <p>Mesurée sur la date de création et non sur le code, bien que le code
     * porte l'année et que ce soit lui qu'on prononce : il n'est attribué qu'à
     * la validation, et les demandes encore en attente n'en ont pas. Un filtre
     * sur le code les écartait toutes — celles-là mêmes dont l'arriéré
     * encombre les compteurs.</p>
     *
     * <p>Ni sur la date de prélèvement : un prélèvement de décembre enregistré
     * en janvier relève de la charge de l'année nouvelle, et 244 dossiers de
     * production sont dans ce cas.</p>
     */
    private Integer annee;

    /**
     * Ne garder que les dossiers encore en cours — ni remis, ni annulés.
     *
     * <p>Distinct de {@code status}, qui désigne un état précis : ici on en
     * écarte deux. Une urgence sur un dossier remis n'appelle plus rien, et
     * les compter faisait grossir un chiffre que rien ne pouvait plus faire
     * baisser.</p>
     */
    private Boolean enCours;

    /** Date de prélèvement minimale (inclusif). */
    private LocalDate from;

    /** Date de prélèvement maximale (inclusif). */
    private LocalDate to;

    /** Texte recherché dans le code du bon (recherche insensible à la casse). */
    private String search;

    /** Filtre sur l'identifiant du contrat. */
    private UUID contratId;

    /** Filtre sur l'identifiant du type d'examen. */
    private UUID typeOrderId;

    /**
     * Discipline des bons listés — anatomie pathologique par défaut.
     *
     * <p>Le défaut n'est pas une commodité : les écrans d'anatomie
     * pathologique et l'application mobile n'envoient pas ce critère, et
     * doivent continuer de voir exactement ce qu'ils voyaient avant l'arrivée
     * de la biologie. {@code null} ne filtre rien, pour un appelant interne qui
     * voudrait les deux disciplines.</p>
     */
    private Discipline discipline = Discipline.PATHOLOGY;
}

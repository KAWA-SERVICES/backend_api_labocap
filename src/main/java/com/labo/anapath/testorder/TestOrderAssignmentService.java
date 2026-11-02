package com.labo.anapath.testorder;

import com.labo.anapath.common.dto.PageResponse;

import java.util.UUID;

public interface TestOrderAssignmentService {

    AssignmentResponseDto create(AssignmentRequestDto dto, UUID branchId);

    /** Les lots d'anatomie pathologique (histologie, cytologie, biopsie, pièce opératoire). */
    PageResponse<AssignmentResponseDto> findAll(int page, int size, UUID branchId);

    /**
     * Les lots d'une discipline.
     *
     * <p>En anatomie pathologique, la liste d'avant, inchangée : lots de
     * cyto/histo. Dans une autre discipline, les lots dont les demandes en
     * relèvent.</p>
     */
    PageResponse<AssignmentResponseDto> findAll(int page, int size, UUID branchId,
                                                com.labo.anapath.common.Discipline discipline);

    PageResponse<AssignmentResponseDto> findAllImmuno(int page, int size, UUID branchId);

    /** À qui la demande a été confiée, de la première fois à aujourd'hui. */
    HistoriqueAffectationDto historiqueDe(UUID demandeId, UUID branchId);

    AssignmentDetailResponseDto addDetail(UUID assignmentId, AssignmentDetailRequestDto dto);

    AssignmentPrintDto getPrintData(UUID assignmentId);

    AssignmentResponseDto update(UUID id, AssignmentRequestDto dto);

    /**
     * Corrige les étiquettes et la note d'une demande déjà affectée.
     *
     * <p>Une étiquette décrit l'état d'un prélèvement au moment où on le range,
     * et cet état change : « Immuno non payé » devient « Immuno payé » quand la
     * caisse encaisse, parfois le lendemain. Sans ce point d'entrée, il fallait
     * retirer la demande du lot et l'y remettre — ce qui perdait sa note et
     * faisait disparaître la ligne d'un écran que quelqu'un consultait
     * peut-être.</p>
     */
    AssignmentDetailResponseDto modifierDetail(UUID detailId,
                                               CorrectionDetailDto correction,
                                               UUID branchId);

    void deleteDetail(UUID detailId);

    /**
     * Le vocabulaire d'étiquettes déjà employé par la branche.
     *
     * <p>Proposé à la saisie pour éviter qu'une même étiquette soit réécrite —
     * ou réécrite autrement — d'une affectation à l'autre.</p>
     */
    java.util.List<String> etiquettesConnues(java.util.UUID branchId);

    /**
     * Verse une étiquette au catalogue, sans attendre qu'une demande soit
     * affectée.
     *
     * <p>Une étiquette n'existait qu'au moment où l'ajout d'une demande
     * aboutissait : la saisir puis renoncer la perdait, et rien ne permettait
     * d'en déclarer une à l'avance. Le vocabulaire d'un laboratoire se pose
     * pourtant avant qu'on s'en serve.</p>
     *
     * @return le catalogue complet, étiquette comprise
     */
    java.util.List<String> ajouterAuCatalogue(java.util.UUID branchId, String valeur);

    /** Le catalogue tel qu'on l'administre : identifiants et usages compris. */
    java.util.List<EtiquetteDto> catalogue(java.util.UUID branchId);

    /**
     * Corrige le texte d'une étiquette.
     *
     * <p>Ne touche pas aux affectations déjà enregistrées : celles-ci
     * consignent ce qui a été porté sur le contenant ce jour-là. Réécrire
     * après coup falsifierait une trace.</p>
     */
    EtiquetteDto renommer(java.util.UUID branchId, java.util.UUID id, String valeur);

    /** Retire une étiquette des propositions, sans effacer aucune trace. */
    void retirer(java.util.UUID branchId, java.util.UUID id);

    /**
     * La file de travail d'un médecin : ses demandes, tous lots confondus.
     *
     * <p>Une seule liste plate et non une liste de lots : le médecin traite des
     * dossiers, il ne navigue pas de bordereau en bordereau. Le code du lot
     * reste rappelé sur chaque ligne.</p>
     */
    java.util.List<DemandeDuMedecinDto> fileDuMedecin(java.util.UUID docteurId,
                                                      Integer annee,
                                                      com.labo.anapath.common.Discipline discipline);

    /** Une page de la file, filtrée au serveur. */
    com.labo.anapath.common.dto.PageResponse<DemandeDuMedecinDto> pageDeLaFile(
            java.util.UUID docteurId, FiltreFileDuMedecin filtre, int page, int taille);

    /** Les lots présents dans la file, pour n'offrir au filtre que ce qui existe. */
    java.util.List<String> lotsDeLaFile(java.util.UUID docteurId, Integer annee,
                                        com.labo.anapath.common.Discipline discipline);

    /** La répartition du périmètre courant entre les trois états. */
    ResumeFileDto resumeDeLaFile(java.util.UUID docteurId, FiltreFileDuMedecin filtre);

    /** Combien de dossiers de sa file datent d'avant l'année donnée. */
    long arriereDuMedecin(java.util.UUID docteurId, int annee,
                          com.labo.anapath.common.Discipline discipline);

    /**
     * Change où en est le médecin sur une demande.
     *
     * <p>Depuis le web seulement — sur le téléphone la file est en lecture.
     * On referme un dossier sur un poste de travail, pas en le consultant entre
     * deux couloirs.</p>
     */
    DemandeDuMedecinDto changerStatutDuMedecin(java.util.UUID detailId,
                                               String statut,
                                               java.util.UUID branchId);

    /**
     * Les dossiers restant à traiter du médecin, dans l'ordre où les prendre :
     * urgents, puis en retard (du plus ancien), puis par date de lot.
     */
    java.util.List<DemandeDuMedecinDto> prioritesDeLaFile(java.util.UUID docteurId, int limite,
                                                         com.labo.anapath.common.Discipline discipline);

    /**
     * Confie une demande à un médecin, dans son lot du jour (créé au besoin).
     *
     * <p>Le chemin de l'écran des lots, en un geste : c'est sur les lots que se
     * calcule la file du médecin. Refuse un destinataire qui n'est pas médecin
     * signataire actif. Une demande déjà confiée à un autre médecin lève
     * {@link com.labo.anapath.common.exception.ReaffectationNonConfirmeeException}
     * sauf si {@code confirmerReaffectation}.</p>
     */
    AssignmentDetailResponseDto affecterAuMedecin(java.util.UUID testOrderId, java.util.UUID medecinId,
                                                  boolean confirmerReaffectation, java.util.UUID branchId);
}

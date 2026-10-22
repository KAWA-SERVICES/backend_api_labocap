package com.labo.anapath.testorder;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.dashboard.DashboardDto;
import com.labo.anapath.dashboard.DashboardProjection;
import com.labo.anapath.patient.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository Spring Data JPA pour l'entité {@link TestOrder}.
 *
 * <p>Étend {@link JpaSpecificationExecutor} pour permettre les requêtes dynamiques
 * via {@link TestOrderSpecification}.
 */
@Repository
public interface TestOrderRepository extends JpaRepository<TestOrder, UUID>, JpaSpecificationExecutor<TestOrder> {

    /**
     * Vérifie si un bon d'examen existe pour le patient donné.
     * Utilisé pour contrôler l'existence d'antécédents avant suppression du patient.
     *
     * @param patient le patient à vérifier
     * @return {@code true} si au moins un bon existe pour ce patient
     */
    boolean existsByPatient(Patient patient);

    /**
     * Récupère un bon d'examen par son identifiant et sa branche.
     * Assure l'isolation multi-tenant : un bon ne peut être accédé que par sa branche propriétaire.
     *
     * @param id       identifiant UUID du bon
     * @param branchId identifiant de la branche
     * @return le bon s'il appartient à la branche, sinon {@link java.util.Optional#empty()}
     */
    Optional<TestOrder> findByIdAndBranchId(UUID id, UUID branchId);

    /**
     * Retourne tous les bons d'examen d'un patient, triés du plus récent au plus ancien.
     *
     * @param patient le patient concerné
     * @return liste ordonnée par date de création décroissante
     */
    List<TestOrder> findByPatientOrderByCreatedAtDesc(Patient patient);

    /**
     * Récupère les bons d'examen d'une branche ayant un code généré pour une année donnée,
     * triés par code décroissant. Utilisé par l'algorithme de génération de code
     * pour déterminer le dernier numéro de séquence de l'année.
     *
     * @param branchId identifiant de la branche
     * @param year     année civile (ex. 2026)
     * @param pageable pagination (en pratique : page 0, taille 1 pour obtenir le dernier)
     * @return liste des bons correspondants
     */
    // -------------------------------------------------------------------------
    // Dashboard — comptages globaux
    // -------------------------------------------------------------------------

    long countByBranchId(UUID branchId);

    /**
     * Nombre de bons d'une discipline dans la branche.
     *
     * <p>Le tableau de bord ne compte que l'anatomie pathologique tant que la
     * répartition par discipline n'y est pas affichée : un total mêlant les deux
     * changerait sans prévenir un chiffre que le laboratoire suit.</p>
     */
    long countByBranchIdAndDiscipline(UUID branchId, Discipline discipline);

    /** Nombre de bons d'examen consommés par un contrat (équivalent Laravel {@code $contrat->orders->count()}). */
    long countByContratId(UUID contratId);

    /**
     * Recherche un bon d'examen par son code affiché, dans la branche courante.
     * Utilisé par le signalement, où l'utilisateur saisit le code (« 26-0008 »)
     * et où c'est le serveur qui le résout, comme en Laravel.
     */
    java.util.Optional<TestOrder> findByCodeAndBranchId(String code, UUID branchId);

    @Query("SELECT COUNT(t) FROM TestOrder t WHERE t.branchId = :branchId AND t.createdAt >= :start AND t.createdAt <= :end"
            + " AND t.discipline = :discipline")
    long countByBranchIdAndCreatedAtBetween(@Param("branchId") UUID branchId,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end,
                                             @Param("discipline") Discipline discipline);

    /**
     * Les dossiers sans aucun compte rendu, ouverts depuis {@code depuis}.
     *
     * <p>La borne existe parce que le chiffre servait d'indicateur de charge et
     * n'en était plus un : il comptait 331 dossiers, dont 237 ouverts avant
     * cette année. Le travail à faire aujourd'hui s'y perdait, et le nombre ne
     * pouvait plus redescendre.</p>
     */
    @Query(value = """
            SELECT COUNT(*) FROM test_orders t
            WHERE t.branch_id = :branchId AND t.deleted_at IS NULL
              AND t.created_at >= :depuis
              AND t.discipline = :discipline
              AND NOT EXISTS (SELECT 1 FROM reports r WHERE r.test_order_id = t.id AND r.deleted_at IS NULL)
            """, nativeQuery = true)
    long countByBranchIdAndReportIsNull(@Param("branchId") UUID branchId,
                                        @Param("depuis") LocalDateTime depuis,
                                        @Param("discipline") String discipline);

    /** Les demandes en attente depuis trop longtemps, ouvertes depuis {@code depuis}. */
    @Query("""
            SELECT COUNT(t) FROM TestOrder t
            WHERE t.branchId = :branchId
              AND t.status = com.labo.anapath.testorder.TestOrderStatus.PENDING
              AND t.createdAt < :before
              AND t.createdAt >= :depuis
              AND t.discipline = :discipline
            """)
    long countByBranchIdAndStatusPendingAndCreatedAtBefore(@Param("branchId") UUID branchId,
                                                            @Param("before") LocalDateTime before,
                                                            @Param("depuis") LocalDateTime depuis,
                                                            @Param("discipline") Discipline discipline);

    /**
     * Demandes d'examen sans macro depuis plus de {@code days} jours (alerte
     * "macro non faite"). Réplique la règle Laravel : test_orders absents de
     * test_pathology_macros et créés il y a plus de N jours.
     *
     * <p>Restreinte à une discipline : la macroscopie n'existe qu'en anatomie
     * pathologique, et un bon de biologie serait signalé à tort « sans macro »
     * pour toujours.</p>
     */
    @Query(value = """
            SELECT t.* FROM test_orders t
            WHERE t.branch_id = :branchId
              AND t.deleted_at IS NULL
              AND DATE(t.created_at) <= CURRENT_DATE - :days
              AND t.discipline = :discipline
              AND NOT EXISTS (
                  SELECT 1 FROM test_pathology_macros m
                  WHERE m.test_order_id = t.id AND m.deleted_at IS NULL)
            ORDER BY t.created_at ASC
            LIMIT :maxRows
            """, nativeQuery = true)
    List<TestOrder> findOverdueWithoutMacro(@Param("branchId") UUID branchId,
                                            @Param("days") int days,
                                            @Param("maxRows") int maxRows,
                                            @Param("discipline") String discipline);

    /**
     * Demandes d'examen dont le compte-rendu n'est toujours pas validé depuis plus de
     * {@code days} jours (alerte "compte-rendu non fait"). Réplique la règle Laravel :
     * {@code test_orders} créés il y a plus de N jours dont le rapport associé n'est pas
     * terminé (statut différent de VALIDATED/DELIVERED). Le destinataire est le
     * pathologiste assigné ({@code attribuate_doctor_id}).
     */
    @Query(value = """
            SELECT t.code AS testOrderCode,
                   u.email AS email,
                   CONCAT(u.firstname, ' ', u.lastname) AS doctorName
            FROM test_orders t
            JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            JOIN users u ON u.id = t.attribuate_doctor_id AND u.deleted_at IS NULL
            WHERE t.branch_id = :branchId
              AND t.deleted_at IS NULL
              AND r.status NOT IN ('VALIDATED', 'DELIVERED')
              AND DATE(t.created_at) <= CURRENT_DATE - :days
              AND t.discipline = :discipline
            ORDER BY t.created_at ASC
            LIMIT :maxRows
            """, nativeQuery = true)
    List<ReportNonFaitProjection> findOverdueWithoutReport(@Param("branchId") UUID branchId,
                                                           @Param("days") int days,
                                                           @Param("maxRows") int maxRows,
                                                           @Param("discipline") String discipline);

    // -------------------------------------------------------------------------
    // Dashboard — top examens
    // -------------------------------------------------------------------------

    @Query(value = """
            SELECT dto.test_name as testName, COUNT(*) as totalDemandes
            FROM detail_test_orders dto
            JOIN test_orders t ON dto.test_order_id = t.id
            WHERE t.branch_id = :branchId AND t.deleted_at IS NULL
              AND t.discipline = :discipline
            GROUP BY dto.test_name
            ORDER BY totalDemandes DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DashboardProjection.TopExamen> getTopExamensByBranchId(@Param("branchId") UUID branchId,
                                                          @Param("limit") int limit,
                                                          @Param("discipline") String discipline);

    // -------------------------------------------------------------------------
    // Dashboard — stats docteurs
    // -------------------------------------------------------------------------

    /**
     * Parité Laravel (`HomeController@index`, boucle `getUsersByRole('docteur')`) :
     * le tableau part de la LISTE DES DOCTEURS, pas des demandes. Un docteur sans
     * aucune demande affectée doit donc apparaître avec 0/0, et un utilisateur qui
     * n'a pas le rôle docteur ne doit pas apparaître même s'il porte des demandes.
     * Partir de `test_orders` inversait les deux règles.
     *
     * « Traité » = compte rendu VALIDATED **ou** DELIVERED, comme le
     * `whereIn('status', ['VALIDATED', 'DELIVERED'])` de Laravel : ne compter que
     * VALIDATED perdait les comptes rendus déjà remis au patient.
     */
    @Query(value = """
            SELECT u.id::text as id, CONCAT(u.lastname, ' ', u.firstname) as doctor,
                   COUNT(DISTINCT t.id) as assigne,
                   COUNT(DISTINCT CASE WHEN r.status IN ('VALIDATED', 'DELIVERED') THEN t.id END) as traite
            FROM users u
            JOIN user_roles ur ON ur.user_id = u.id
            JOIN roles ro ON ro.id = ur.role_id AND ro.slug = 'docteur'
            LEFT JOIN test_orders t ON t.attribuate_doctor_id = u.id
                 AND t.branch_id = :branchId AND t.deleted_at IS NULL
                 -- Dans la jointure et non dans le WHERE : un docteur sans
                 -- demande de cette discipline doit rester affiché à 0/0.
                 AND t.discipline = :discipline
            LEFT JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            WHERE u.branch_id = :branchId AND u.deleted_at IS NULL
            GROUP BY u.id, u.lastname, u.firstname
            ORDER BY assigne DESC
            """, nativeQuery = true)
    List<DashboardProjection.DoctorStat> getDoctorStatsByBranchId(@Param("branchId") UUID branchId,
                                                                  @Param("discipline") String discipline);

    // -------------------------------------------------------------------------
    // Dashboard — stats mensuelles
    //
    // Parité Laravel : `HomeController@index` filtre avec
    // `whereMonth('test_orders.created_at', $mois_souhaite)`, c'est-à-dire sur le
    // NUMÉRO DU MOIS SEUL, sans l'année. Un août agrège donc tous les mois d'août
    // de la base, toutes années confondues. On reproduit ce comportement à
    // l'identique : filtrer aussi sur l'année vidait le bloc « STATISTIQUE
    // MENSUELLE » du tableau de bord dès qu'aucune demande n'était enregistrée
    // dans le mois courant de l'année en cours, alors que l'application Laravel
    // de référence affiche des chiffres.
    // -------------------------------------------------------------------------

    @Query(value = """
            SELECT COUNT(DISTINCT dto.id)
            FROM test_orders t
            JOIN detail_test_orders dto ON dto.test_order_id = t.id
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL
              AND t.discipline = :discipline
            """, nativeQuery = true)
    long countByBranchIdAndMonth(@Param("branchId") UUID branchId,
                                  @Param("month") int month,
                                  @Param("discipline") String discipline);

    @Query(value = """
            SELECT COALESCE(SUM(lt.price), 0)
            FROM test_orders t
            JOIN detail_test_orders dto ON dto.test_order_id = t.id
            JOIN lab_tests lt ON dto.lab_test_id = lt.id
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL
            """, nativeQuery = true)
    BigDecimal sumPriceByBranchIdAndMonth(@Param("branchId") UUID branchId,
                                           @Param("month") int month);

    @Query(value = """
            SELECT COUNT(DISTINCT t.patient_id)
            FROM test_orders t
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL
              AND t.discipline = :discipline
            """, nativeQuery = true)
    long countPatientsByBranchIdAndMonth(@Param("branchId") UUID branchId,
                                          @Param("month") int month,
                                          @Param("discipline") String discipline);

    @Query(value = """
            SELECT h.name as nom, COUNT(DISTINCT t.patient_id) as totalPatients
            FROM test_orders t JOIN hospitals h ON t.hospital_id = h.id
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL AND h.deleted_at IS NULL
              AND t.discipline = :discipline
            GROUP BY h.id, h.name ORDER BY totalPatients DESC
            """, nativeQuery = true)
    List<DashboardProjection.ByItem> countByHospitalAndMonth(@Param("branchId") UUID branchId,
                                                       @Param("month") int month,
                                                       @Param("discipline") String discipline);

    @Query(value = """
            SELECT d.name as nom, COUNT(DISTINCT t.patient_id) as totalPatients
            FROM test_orders t JOIN doctors d ON t.doctor_id = d.id
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL AND d.deleted_at IS NULL
              AND t.discipline = :discipline
            GROUP BY d.id, d.name HAVING COUNT(DISTINCT t.patient_id) > 5
            ORDER BY totalPatients DESC
            """, nativeQuery = true)
    List<DashboardProjection.ByItem> countByDoctorAndMonth(@Param("branchId") UUID branchId,
                                                     @Param("month") int month,
                                                     @Param("discipline") String discipline);

    /**
     * Patients du mois par type de demande.
     *
     * <p>Non filtrée par discipline : la jointure interne sur {@code type_orders}
     * écarte déjà les bons de biologie, qui ne portent pas de type de demande
     * d'anatomie pathologique.</p>
     */
    @Query(value = """
            SELECT tp.title as nom, COUNT(DISTINCT t.patient_id) as totalPatients
            FROM test_orders t JOIN type_orders tp ON t.type_order_id = tp.id
            WHERE t.branch_id = :branchId AND EXTRACT(MONTH FROM t.created_at) = :month
              AND t.deleted_at IS NULL
            GROUP BY tp.id, tp.title ORDER BY totalPatients DESC
            """, nativeQuery = true)
    List<DashboardProjection.ByItem> countByTypeOrderAndMonth(@Param("branchId") UUID branchId,
                                                        @Param("month") int month);

    // -------------------------------------------------------------------------
    // Dashboard pathologiste — bons affectés via attribuate_doctor_id
    // -------------------------------------------------------------------------

    @Query(value = """
            SELECT t.id::text as id, t.code as code, t.created_at::text as createdAt,
                   p.firstname as patientFirstname, p.lastname as patientLastname,
                   CASE WHEN r.status IN ('VALIDATED','DELIVERED') THEN 1 ELSE 0 END as reportStatus
            FROM test_orders t
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            WHERE t.attribuate_doctor_id = :userId
              AND t.branch_id = :branchId AND t.deleted_at IS NULL
              AND t.discipline = :discipline
            ORDER BY t.created_at DESC
            """, nativeQuery = true)
    List<DashboardProjection.DoctorOrder> findAllByAttribuateDoctorId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("discipline") String discipline);

    @Query(value = """
            SELECT t.id::text as id, t.code as code, t.created_at::text as createdAt,
                   p.firstname as patientFirstname, p.lastname as patientLastname,
                   CASE WHEN r.status IN ('VALIDATED','DELIVERED') THEN 1 ELSE 0 END as reportStatus
            FROM test_orders t
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            WHERE t.attribuate_doctor_id = :userId
              AND t.branch_id = :branchId AND t.deleted_at IS NULL
              AND t.discipline = :discipline
              AND DATE(r.updated_at) = :today
            ORDER BY t.created_at DESC
            """, nativeQuery = true)
    List<DashboardProjection.DoctorOrder> findTodayByAttribuateDoctorId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("today") java.time.LocalDate today,
            @Param("discipline") String discipline);

    @Query(value = """
            SELECT t.* FROM test_orders t
            WHERE t.deleted_at IS NULL AND t.branch_id = :branchId
              AND t.code IS NOT NULL
              AND EXTRACT(YEAR FROM t.created_at) = :year
            ORDER BY t.code DESC
            LIMIT :#{#pageable.pageSize} OFFSET :#{#pageable.offset}
            """, nativeQuery = true)
    List<TestOrder> findByBranchIdAndCodeNotNullAndYear(
            @Param("branchId") UUID branchId,
            @Param("year") int year,
            Pageable pageable);

    /**
     * Renvoie la plus grande séquence numérique parmi les codes de la branche
     * dont l'année (les 2 chiffres avant le {@code -}) correspond à {@code yearToken}.
     *
     * <p>Robuste face aux données migrées : le tri est <b>numérique</b> (sur la
     * séquence extraite en fin de code), et le filtre d'année porte sur le
     * <b>code lui-même</b> (et non {@code created_at}), ce qui évite de calculer
     * un « prochain » code qui entre en collision avec un code déjà existant.
     *
     * <p>Volontairement sans filtre de discipline : la séquence est unique pour
     * l'année, toutes disciplines confondues, et le code porte une contrainte
     * d'unicité sur toute la table.</p>
     *
     * @param branchId  identifiant de la branche
     * @param yearToken les 2 chiffres de l'année (ex. « 26 »)
     * @return la séquence max (0 si aucun code pour l'année)
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(regexp_replace(t.code, '^.*-', '') AS INTEGER)), 0)
            FROM test_orders t
            WHERE t.deleted_at IS NULL AND t.branch_id = :branchId
              AND t.code ~ (:yearToken || '-[0-9]+$')
            """, nativeQuery = true)
    int findMaxSequenceForYear(
            @Param("branchId") UUID branchId,
            @Param("yearToken") String yearToken);

    /**
     * Teste l'existence d'un code, <b>y compris sur les bons supprimés</b>
     * (l'index d'unicité de la colonne {@code code} porte sur toutes les lignes).
     * Utilisé en pré-vérification avant l'insertion pour éviter la collision.
     *
     * @param code code candidat
     * @return true si un bon (même supprimé) porte déjà ce code
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM test_orders WHERE code = :code)", nativeQuery = true)
    boolean existsByCodeIncludingDeleted(@Param("code") String code);

    // -------------------------------------------------------------------------
    // Myspace — statistiques des bons assignés à un utilisateur
    // -------------------------------------------------------------------------

    /**
     * Compte tous les bons d'examen assignés à un utilisateur pour une branche.
     *
     * @param userId   identifiant de l'utilisateur assigné
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return nombre total de bons assignés
     */
    @Query("SELECT COUNT(t) FROM TestOrder t WHERE t.branchId = :branchId " +
           "AND t.discipline = :discipline " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId)")
    long countByAssignedToUserIdAndBranchId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("seeAll") boolean seeAll,
            @Param("discipline") Discipline discipline);

    /**
     * Compte les bons d'examen assignés à un utilisateur pour une branche et un statut donnés.
     *
     * @param userId   identifiant de l'utilisateur assigné
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @param status   statut à filtrer
     * @return nombre de bons correspondants
     */
    @Query("SELECT COUNT(t) FROM TestOrder t WHERE t.assignedToUserId = :userId AND t.branchId = :branchId AND t.status = :status"
            + " AND t.discipline = :discipline")
    long countByAssignedToUserIdAndBranchIdAndStatus(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("status") TestOrderStatus status,
            @Param("discipline") Discipline discipline);

    /**
     * Compte les bons d'examen urgents assignés à un utilisateur pour une branche.
     *
     * @param userId   identifiant de l'utilisateur assigné
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return nombre de bons urgents
     */
    @Query("SELECT COUNT(t) FROM TestOrder t WHERE t.branchId = :branchId AND t.isUrgent = true " +
           "AND t.discipline = :discipline " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId)")
    long countUrgentByAssignedToUserIdAndBranchId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("seeAll") boolean seeAll,
            @Param("discipline") Discipline discipline);

    /**
     * Compte les bons d'examen en retard (PENDING assignés il y a plus de 48 h).
     *
     * @param userId          identifiant de l'utilisateur assigné
     * @param branchId        identifiant de la branche (isolation multi-tenant)
     * @param cutoff          date limite — les bons dont {@code assignmentDate} est antérieure
     *                        à cette valeur sont considérés en retard
     * @return nombre de bons en retard
     */
    @Query("SELECT COUNT(t) FROM TestOrder t " +
           "LEFT JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id " +
           "WHERE t.branchId = :branchId " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId) " +
           "AND (r IS NULL OR r.status NOT IN (com.labo.anapath.report.ReportStatus.VALIDATED, com.labo.anapath.report.ReportStatus.DELIVERED)) " +
           "AND t.assignmentDate IS NOT NULL AND t.assignmentDate < :cutoff " +
           "AND t.discipline = :discipline")
    long countLateByAssignedToUserIdAndBranchId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("seeAll") boolean seeAll,
            @Param("discipline") Discipline discipline);

    /**
     * Compte les bons assignés dont le rapport n'est pas encore terminé
     * (rapport inexistant, ou statut autre que VALIDATED/DELIVERED).
     */
    @Query("SELECT COUNT(t) FROM TestOrder t " +
           "LEFT JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id " +
           "WHERE t.branchId = :branchId " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId) " +
           "AND (r IS NULL OR r.status NOT IN (com.labo.anapath.report.ReportStatus.VALIDATED, com.labo.anapath.report.ReportStatus.DELIVERED)) " +
           "AND t.discipline = :discipline")
    long countAssignedReportPending(@Param("userId") UUID userId, @Param("branchId") UUID branchId,
                                    @Param("seeAll") boolean seeAll,
                                    @Param("discipline") Discipline discipline);

    /**
     * Compte les bons assignés dont le rapport est terminé (VALIDATED ou DELIVERED).
     */
    @Query("SELECT COUNT(t) FROM TestOrder t " +
           "JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id " +
           "WHERE t.branchId = :branchId " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId) " +
           "AND r.status IN (com.labo.anapath.report.ReportStatus.VALIDATED, com.labo.anapath.report.ReportStatus.DELIVERED) " +
           "AND t.discipline = :discipline")
    long countAssignedReportDone(@Param("userId") UUID userId, @Param("branchId") UUID branchId,
                                 @Param("seeAll") boolean seeAll,
                                 @Param("discipline") Discipline discipline);

    /**
     * Compte les bons d'examen immuno assignés dont le rapport n'est pas terminé.
     *
     * @param userId   identifiant de l'utilisateur assigné
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @param typeIds  liste des UUID des types immuno
     * @return nombre de bons immuno en attente
     */
    @Query("SELECT COUNT(t) FROM TestOrder t " +
           "LEFT JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id " +
           "WHERE t.branchId = :branchId " +
           "AND (:seeAll = TRUE OR t.assignedToUserId = :userId) " +
           "AND t.typeOrder.id IN :typeIds " +
           "AND (r IS NULL OR r.status NOT IN (com.labo.anapath.report.ReportStatus.VALIDATED, com.labo.anapath.report.ReportStatus.DELIVERED))")
    long countImmunoPendingByAssignedToUserId(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("typeIds") List<UUID> typeIds,
            @Param("seeAll") boolean seeAll);

    // -------------------------------------------------------------------------
    // Myspace — liste paginée des bons assignés à un utilisateur
    // -------------------------------------------------------------------------

    /**
     * Retourne la page de bons d'examen assignés à un utilisateur, avec filtres optionnels
     * sur le statut et une recherche textuelle (code du bon ou nom du patient).
     *
     * @param userId   identifiant de l'utilisateur assigné
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @param status   statut à filtrer (peut être null pour tous les statuts)
     * @param search   motif de recherche insensible à la casse (peut être null)
     * @param discipline nom de la discipline ({@code PATHOLOGY}, {@code BIOLOGY})
     * @param pageable paramètres de pagination
     * @return page de bons correspondants
     */
    @Query(value = """
            SELECT t.* FROM test_orders t
            JOIN patients pat ON pat.id = t.patient_id AND pat.deleted_at IS NULL
            LEFT JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            WHERE t.deleted_at IS NULL
              AND (:seeAll = TRUE OR t.assigned_to_user_id = :userId)
              AND t.branch_id = :branchId
              AND t.discipline = :discipline
              AND (:status IS NULL
                   OR (:status = 'PENDING'   AND (r.id IS NULL OR r.status NOT IN ('VALIDATED', 'DELIVERED')))
                   OR (:status = 'VALIDATED' AND r.status IN ('VALIDATED', 'DELIVERED'))
                   OR (:status = 'DELIVERED' AND r.status = 'DELIVERED'))
              AND (CAST(:typeOrderId AS uuid) IS NULL OR t.type_order_id = CAST(:typeOrderId AS uuid))
              AND (:priority IS NULL
                   OR (:priority = 'urgent' AND t.is_urgent = true)
                   OR (:priority = 'late' AND t.status::text = 'PENDING'
                       AND t.assignment_date IS NOT NULL
                       AND t.assignment_date < (now() - interval '21 days')))
              AND (CAST(:fromDate AS date) IS NULL OR DATE(t.created_at) >= CAST(:fromDate AS date))
              AND (CAST(:toDate AS date) IS NULL OR DATE(t.created_at) <= CAST(:toDate AS date))
              AND (:search IS NULL OR (
                    lower(coalesce(t.code,         '')) LIKE lower('%' || CAST(:search AS text) || '%')
                 OR lower(coalesce(pat.firstname,  '')) LIKE lower('%' || CAST(:search AS text) || '%')
                 OR lower(coalesce(pat.lastname,   '')) LIKE lower('%' || CAST(:search AS text) || '%')
              ))
            ORDER BY t.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM test_orders t
            JOIN patients pat ON pat.id = t.patient_id AND pat.deleted_at IS NULL
            LEFT JOIN reports r ON r.test_order_id = t.id AND r.deleted_at IS NULL
            WHERE t.deleted_at IS NULL
              AND (:seeAll = TRUE OR t.assigned_to_user_id = :userId)
              AND t.branch_id = :branchId
              AND t.discipline = :discipline
              AND (:status IS NULL
                   OR (:status = 'PENDING'   AND (r.id IS NULL OR r.status NOT IN ('VALIDATED', 'DELIVERED')))
                   OR (:status = 'VALIDATED' AND r.status IN ('VALIDATED', 'DELIVERED'))
                   OR (:status = 'DELIVERED' AND r.status = 'DELIVERED'))
              AND (CAST(:typeOrderId AS uuid) IS NULL OR t.type_order_id = CAST(:typeOrderId AS uuid))
              AND (:priority IS NULL
                   OR (:priority = 'urgent' AND t.is_urgent = true)
                   OR (:priority = 'late' AND t.status::text = 'PENDING'
                       AND t.assignment_date IS NOT NULL
                       AND t.assignment_date < (now() - interval '21 days')))
              AND (CAST(:fromDate AS date) IS NULL OR DATE(t.created_at) >= CAST(:fromDate AS date))
              AND (CAST(:toDate AS date) IS NULL OR DATE(t.created_at) <= CAST(:toDate AS date))
              AND (:search IS NULL OR (
                    lower(coalesce(t.code,         '')) LIKE lower('%' || CAST(:search AS text) || '%')
                 OR lower(coalesce(pat.firstname,  '')) LIKE lower('%' || CAST(:search AS text) || '%')
                 OR lower(coalesce(pat.lastname,   '')) LIKE lower('%' || CAST(:search AS text) || '%')
              ))
            """,
            nativeQuery = true)
    Page<TestOrder> findMyspaceOrders(
            @Param("userId") UUID userId,
            @Param("branchId") UUID branchId,
            @Param("status") String status,
            @Param("typeOrderId") String typeOrderId,
            @Param("priority") String priority,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("search") String search,
            @Param("seeAll") boolean seeAll,
            @Param("discipline") String discipline,
            Pageable pageable);

    // -------------------------------------------------------------------------
    // Immunohistochimie — comptage des bons en attente (rapport DRAFT ou inexistant)
    // -------------------------------------------------------------------------

    /**
     * Compte les bons d'examen immuno de la branche dont le rapport associé
     * est en statut {@code DRAFT} ou n'existe pas encore.
     *
     * @param branchId identifiant de la branche
     * @param typeIds  liste des UUID des types immuno
     * @return nombre de bons immuno en attente
     */
    @Query("""
            SELECT COUNT(t) FROM TestOrder t
            LEFT JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id
            WHERE t.branchId = :branchId
              AND t.typeOrder.id IN :typeIds
              AND (r.status = com.labo.anapath.report.ReportStatus.DRAFT OR r IS NULL)
            """)
    long countImmunoPending(@Param("branchId") UUID branchId,
                            @Param("typeIds") List<UUID> typeIds);

    /**
     * Compte les bons d'examen de la branche, restreints aux types donnés, dont le
     * compte rendu associé <b>existe</b> et est en statut {@code DRAFT} ou
     * {@code PENDING_REVIEW}.
     *
     * <p>Reprise exacte des helpers Laravel {@code getnbrTestOrderpending()} et
     * {@code getnbrTestOrderImmunopending()}, qui utilisent {@code whereHas('report')}
     * (le compte rendu doit donc exister) avec {@code whereIn('status', ['DRAFT',
     * 'PENDING_REVIEW'])}.</p>
     *
     * @param branchId identifiant de la branche
     * @param typeIds  liste des UUID des types de bons à compter
     * @return nombre de bons en attente de compte rendu
     */
    @Query("""
            SELECT COUNT(t) FROM TestOrder t
            JOIN com.labo.anapath.report.Report r ON r.testOrder.id = t.id
            WHERE t.branchId = :branchId
              AND t.typeOrder.id IN :typeIds
              AND r.status IN (com.labo.anapath.report.ReportStatus.DRAFT,
                               com.labo.anapath.report.ReportStatus.PENDING_REVIEW)
            """)
    long countPendingReportByTypeIds(@Param("branchId") UUID branchId,
                                     @Param("typeIds") List<UUID> typeIds);

    /**
     * L'index des demandes que l'application mobile garde pour travailler
     * sans réseau.
     *
     * <p>Projeté directement dans le DTO, sans charger les entités : les
     * 3 133 lignes du jeu de travail amèneraient sinon leurs patients, leurs
     * contrats et leurs types d'examen, pour quatre champs retenus.</p>
     *
     * <p>Le périmètre est borné par le statut et par la date : les demandes
     * remises ne se scannent plus, et celles d'avant l'année précédente
     * n'arrivent plus au laboratoire. Sans ces bornes l'index pèserait le
     * double, dont la moitié ne servirait jamais.</p>
     *
     * <h2>Pourquoi deux bornes de date, et un second critère de tri</h2>
     *
     * <p>L'index descend page par page, et le téléphone met plusieurs minutes
     * à les rapatrier toutes sur un lien médiocre. Pendant ce temps le
     * comptoir enregistre des demandes : chacune s'insère en tête du tri par
     * date décroissante et décale tout d'un rang, de sorte qu'une entrée
     * passerait de la page lue à celle qu'on vient de quitter — et manquerait
     * définitivement. [jusqua] fige donc le jeu au premier appel ; ce qui naît
     * après viendra au rapatriement suivant.</p>
     *
     * <p>Le tri sur la seule date ne suffit pas davantage : deux demandes
     * créées dans la même milliseconde — ce que fait une importation — n'ont
     * pas d'ordre défini entre deux requêtes, et la même entrée peut revenir
     * deux fois pendant qu'une autre disparaît. L'identifiant départage.</p>
     */
    @Query(value = """
            SELECT new com.labo.anapath.testorder.EntreeDIndexDto(
                     t.id, t.code,
                     CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, '')),
                     t.status)
            FROM TestOrder t LEFT JOIN t.patient p
            WHERE t.branchId = :branchId
              AND t.deletedAt IS NULL
              AND t.status <> com.labo.anapath.testorder.TestOrderStatus.DELIVERED
              AND t.createdAt >= :depuis
              AND t.createdAt <= :jusqua
            ORDER BY t.createdAt DESC, t.id DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM TestOrder t
            WHERE t.branchId = :branchId
              AND t.deletedAt IS NULL
              AND t.status <> com.labo.anapath.testorder.TestOrderStatus.DELIVERED
              AND t.createdAt >= :depuis
              AND t.createdAt <= :jusqua
            """)
    Page<EntreeDIndexDto> indexPourLeMobile(
            @Param("branchId") UUID branchId,
            @Param("depuis") java.time.LocalDateTime depuis,
            @Param("jusqua") java.time.LocalDateTime jusqua,
            Pageable pageable);
}

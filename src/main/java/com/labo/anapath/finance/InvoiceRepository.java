package com.labo.anapath.finance;

import com.labo.anapath.dashboard.DashboardDto;
import com.labo.anapath.dashboard.DashboardProjection;
import com.labo.anapath.patient.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Dans l'agence donnée seulement : un identifiant d'ailleurs est introuvable. */
    Optional<Invoice> findByIdAndBranchId(UUID id, UUID branchId);

    /**
     * Facture verrouillée jusqu'à la fin de la transaction, pour l'envoi de son
     * SMS : deux envois simultanés (deux agents, ou le bouton pendant l'envoi
     * automatique) liraient sinon tous deux « aucun SMS parti » et créeraient
     * chacun leur lien — le patient recevrait celui qui n'est pas enregistré.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invoice> findWithLockById(UUID id);

    /** Comme {@link #findWithLockById}, limitée à l'agence. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invoice> findWithLockByIdAndBranchId(UUID id, UUID branchId);

    Page<Invoice> findByBranchId(UUID branchId, Pageable pageable);

    List<Invoice> findByPatientOrderByCreatedAtDesc(Patient patient);

    /**
     * Agrège, par patient, le total facturé et le total payé pour un ensemble de
     * patients. Reproduit fidèlement les helpers Laravel
     * {@code getTotalByPatient} / {@code getPaidByPatient} / {@code getNoPaidByPatient}
     * en s'appuyant sur la colonne booléenne {@code paid}.
     * <p>
     * Chaque ligne renvoyée est un {@code Object[]} : [patient_id (UUID),
     * total (BigDecimal), paid (BigDecimal)]. Le restant dû se déduit par
     * {@code total - paid}.
     * </p>
     */
    @Query(value = """
            SELECT patient_id,
                   COALESCE(SUM(total), 0) AS total_amount,
                   COALESCE(SUM(CASE WHEN paid = true THEN total ELSE 0 END), 0) AS paid_amount
            FROM invoices
            WHERE patient_id IN :patientIds AND deleted_at IS NULL
            GROUP BY patient_id
            """, nativeQuery = true)
    List<Object[]> sumTotalsByPatientIds(@Param("patientIds") Collection<UUID> patientIds);

    Optional<Invoice> findByTestOrderId(UUID testOrderId);

    /**
     * Retrouve la facture d'un jeton de lien public.
     *
     * <p>Seul point d'entrée de la route publique : elle ne connaît que le jeton
     * reçu par SMS, jamais l'identifiant de la facture ni la branche.</p>
     */
    Optional<Invoice> findByShareToken(String shareToken);

    List<Invoice> findByTestOrder_IdIn(Collection<UUID> testOrderIds);

    Optional<Invoice> findFirstByContratIdOrderByCreatedAtDesc(UUID contratId);

    /**
     * Retourne la facture la plus ancienne d'un contrat.
     *
     * <p>C'est la facture groupée d'un contrat {@code invoice_unique = true} : celle sur
     * laquelle Laravel cumule les montants, via
     * {@code Invoice::where('contrat_id', $id)->first()} — un {@code LIMIT 1} sans
     * {@code ORDER BY}, donc la première ligne du tas, c'est-à-dire la plus ancienne.
     * Le tri explicite rend le choix déterministe.
     *
     * @param contratId identifiant du contrat
     * @return la facture la plus ancienne du contrat, si elle existe
     */
    Optional<Invoice> findFirstByContratIdOrderByCreatedAtAsc(UUID contratId);

    @Query(value = """
            SELECT i.* FROM invoices i
            WHERE i.deleted_at IS NULL AND i.branch_id = :branchId
              AND i.code IS NOT NULL AND i.code <> 'REGULARISATION'
              AND EXTRACT(YEAR FROM i.created_at) = :year
            ORDER BY i.code DESC
            LIMIT :#{#pageable.pageSize} OFFSET :#{#pageable.offset}
            """, nativeQuery = true)
    List<Invoice> findByBranchIdAndCodeNotNullAndYear(
            @Param("branchId") UUID branchId,
            @Param("year") int year,
            Pageable pageable);

    @Query(value = """
            SELECT i.* FROM invoices i
            WHERE i.deleted_at IS NULL AND i.branch_id = :branchId
              AND i.status_invoice = :statusInvoice
              AND i.code IS NOT NULL
              AND EXTRACT(YEAR FROM i.created_at) = :year
            ORDER BY i.code DESC
            LIMIT :#{#pageable.pageSize} OFFSET :#{#pageable.offset}
            """, nativeQuery = true)
    List<Invoice> findByBranchIdAndStatusInvoiceAndCodeNotNullAndYear(
            @Param("branchId") UUID branchId,
            @Param("statusInvoice") int statusInvoice,
            @Param("year") int year,
            Pageable pageable);

    // Business dashboard — somme des factures payées pour un mois/année donnés
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId AND paid = true " +
                   "AND EXTRACT(MONTH FROM updated_at) = :month AND EXTRACT(YEAR FROM updated_at) = :year " +
                   "AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumPaidByBranchIdAndMonth(
            @Param("branchId") UUID branchId,
            @Param("month") int month,
            @Param("year") int year);

    // Business dashboard — somme des factures payées pour une date donnée
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId AND paid = true " +
                   "AND DATE(updated_at) = :today AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumPaidByBranchIdAndDate(
            @Param("branchId") UUID branchId,
            @Param("today") LocalDate today);

    // Recherche par période — somme ventes payées (statusInvoice=0)
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId AND paid = true AND status_invoice = 0 " +
                   "AND DATE(updated_at) >= :startDate AND DATE(updated_at) <= :endDate " +
                   "AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumVenteByBranchIdAndDateRange(
            @Param("branchId") UUID branchId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Recherche par période — somme avoirs payés (statusInvoice=1)
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId AND paid = true AND status_invoice = 1 " +
                   "AND DATE(updated_at) >= :startDate AND DATE(updated_at) <= :endDate " +
                   "AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumAvoirByBranchIdAndDateRange(
            @Param("branchId") UUID branchId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
            SELECT i.* FROM invoices i
            WHERE i.deleted_at IS NULL
              AND i.branch_id = :branchId
              AND (:paid IS NULL OR i.paid = :paid)
              AND (:statusInvoice IS NULL OR i.status_invoice = :statusInvoice)
              AND (CAST(:startDateTime AS text) IS NULL OR i.created_at >= CAST(:startDateTime AS timestamp))
              AND (CAST(:endDateTime   AS text) IS NULL OR i.created_at <= CAST(:endDateTime   AS timestamp))
              AND (CAST(:search AS text) IS NULL OR (
                    lower(coalesce(i.code,        '')) LIKE lower('%' || :search || '%')
                 OR lower(coalesce(i.client_name, '')) LIKE lower('%' || :search || '%')
              ))
            ORDER BY i.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM invoices i
            WHERE i.deleted_at IS NULL
              AND i.branch_id = :branchId
              AND (:paid IS NULL OR i.paid = :paid)
              AND (:statusInvoice IS NULL OR i.status_invoice = :statusInvoice)
              AND (CAST(:startDateTime AS text) IS NULL OR i.created_at >= CAST(:startDateTime AS timestamp))
              AND (CAST(:endDateTime   AS text) IS NULL OR i.created_at <= CAST(:endDateTime   AS timestamp))
              AND (CAST(:search AS text) IS NULL OR (
                    lower(coalesce(i.code,        '')) LIKE lower('%' || :search || '%')
                 OR lower(coalesce(i.client_name, '')) LIKE lower('%' || :search || '%')
              ))
            """,
            nativeQuery = true)
    Page<Invoice> findFiltered(
            @Param("branchId") UUID branchId,
            @Param("paid") Boolean paid,
            @Param("statusInvoice") Integer statusInvoice,
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime,
            @Param("search") String search,
            Pageable pageable);

    Optional<Invoice> findFirstByCodeMecefOrCodeNormalise(String codeMecef, String codeNormalise);

    Optional<Invoice> findFirstByCodeMecefAndBranchIdOrCodeNormaliseAndBranchId(
            String codeMecef, UUID branchId1, String codeNormalise, UUID branchId2);

    // Recherche par période — total facturé (toutes factures, paid ou non)
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId " +
                   "AND DATE(updated_at) >= :startDate AND DATE(updated_at) <= :endDate " +
                   "AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumTotalByBranchIdAndDateRange(
            @Param("branchId") UUID branchId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Dashboard — total global toutes factures
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices WHERE branch_id = :branchId AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumTotalByBranchId(@Param("branchId") UUID branchId);

    // Dashboard — ventes payées sur une période (status_invoice=0)
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices " +
                   "WHERE branch_id = :branchId AND paid = true AND status_invoice = 0 " +
                   "AND DATE(updated_at) >= :start AND DATE(updated_at) <= :end AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumPaidSalesByBranchIdAndDateRange(@Param("branchId") UUID branchId,
                                                   @Param("start") LocalDate start,
                                                   @Param("end") LocalDate end);

    // Dashboard — comptage factures par statut et type
    @Query(value = "SELECT COUNT(*) FROM invoices WHERE branch_id = :branchId AND status_invoice = :statusInvoice AND paid = :paid AND deleted_at IS NULL",
           nativeQuery = true)
    long countByBranchIdAndStatusInvoiceAndPaid(@Param("branchId") UUID branchId,
                                                 @Param("statusInvoice") int statusInvoice,
                                                 @Param("paid") boolean paid);

    // Dashboard — somme factures par statut et type
    @Query(value = "SELECT COALESCE(SUM(total), 0) FROM invoices WHERE branch_id = :branchId AND status_invoice = :statusInvoice AND paid = :paid AND deleted_at IS NULL",
           nativeQuery = true)
    BigDecimal sumByBranchIdAndStatusInvoiceAndPaid(@Param("branchId") UUID branchId,
                                                     @Param("statusInvoice") int statusInvoice,
                                                     @Param("paid") boolean paid);

    // Dashboard — revenus par jour sur une plage
    @Query(value = """
            SELECT TO_CHAR(DATE(updated_at), 'YYYY-MM-DD') as date, COALESCE(SUM(total), 0) as total
            FROM invoices
            WHERE branch_id = :branchId AND paid = true AND status_invoice = 0
              AND DATE(updated_at) >= :start AND DATE(updated_at) <= :end
              AND deleted_at IS NULL
            GROUP BY DATE(updated_at) ORDER BY DATE(updated_at)
            """, nativeQuery = true)
    List<DashboardProjection.DayRevenue> sumPaidByDayInRange(@Param("branchId") UUID branchId,
                                                       @Param("start") LocalDate start,
                                                       @Param("end") LocalDate end);

    // Rapports — somme des factures par mois/année et statut (vente=0, avoir=1)
    @Query(value = """
            SELECT COALESCE(SUM(total), 0) FROM invoices
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND status_invoice = :statusInvoice
              AND EXTRACT(MONTH FROM created_at) = :month
              AND EXTRACT(YEAR FROM created_at) = :year
            """, nativeQuery = true)
    BigDecimal sumByBranchIdMonthYearAndStatus(
            @Param("branchId") UUID branchId,
            @Param("month") int month,
            @Param("year") int year,
            @Param("statusInvoice") int statusInvoice);

    // Rapports — encaissements (ventes payées) sur le mois/année
    @Query(value = """
            SELECT COALESCE(SUM(total), 0) FROM invoices
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND paid = true AND status_invoice = 0
              AND EXTRACT(MONTH FROM updated_at) = :month
              AND EXTRACT(YEAR FROM updated_at) = :year
            """, nativeQuery = true)
    BigDecimal sumPaidByBranchIdMonthYear(
            @Param("branchId") UUID branchId,
            @Param("month") int month,
            @Param("year") int year);

    // ─────────────────────────────────────────────────────────────────────
    // Rapports sur une PÉRIODE — ventilation par mois
    //
    // Les bornes sont des instants et non des dates, et la borne haute est
    // EXCLUE : `created_at` est un timestamp, si bien qu'un `<= :fin` posé sur
    // une date perdrait toutes les factures du dernier jour émises après minuit.
    // La couche service passe donc `finExclusive = lendemain à 00:00`.
    //
    // Le regroupement ne rend que les mois présents dans les données ; c'est le
    // service qui comble les trous. Un rapport où juillet manquerait se lirait
    // comme un oubli, pas comme un mois sans activité.
    // ─────────────────────────────────────────────────────────────────────

    /** Ventes ou avoirs, agrégés par mois de création. */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM created_at)::int  AS annee,
                   EXTRACT(MONTH FROM created_at)::int AS mois,
                   COALESCE(SUM(total), 0)             AS total
            FROM invoices
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND status_invoice = :statusInvoice
              AND created_at >= :debut AND created_at < :finExclusive
            GROUP BY 1, 2
            ORDER BY 1, 2
            """, nativeQuery = true)
    List<Object[]> sumMonthlyByStatusInPeriod(
            @Param("branchId") UUID branchId,
            @Param("debut") LocalDateTime debut,
            @Param("finExclusive") LocalDateTime finExclusive,
            @Param("statusInvoice") int statusInvoice);

    /**
     * Encaissements agrégés par mois.
     * <p>
     * Filtre sur {@code updated_at}, faute de mieux : la table ne porte aucune
     * date de règlement, {@code paid} n'est qu'un booléen. Une facture réglée en
     * mars puis modifiée en août compte donc dans les encaissements d'août. Le
     * rapport mensuel existant souffre déjà de ce biais — l'étendre à une
     * période le rend seulement plus visible. Le corriger demanderait une
     * colonne {@code paid_at} et une migration.
     * </p>
     */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM updated_at)::int  AS annee,
                   EXTRACT(MONTH FROM updated_at)::int AS mois,
                   COALESCE(SUM(total), 0)             AS total
            FROM invoices
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND paid = true AND status_invoice = 0
              AND updated_at >= :debut AND updated_at < :finExclusive
            GROUP BY 1, 2
            ORDER BY 1, 2
            """, nativeQuery = true)
    List<Object[]> sumMonthlyPaidInPeriod(
            @Param("branchId") UUID branchId,
            @Param("debut") LocalDateTime debut,
            @Param("finExclusive") LocalDateTime finExclusive);

    /** Ventes par contrat sur la période entière — sert la ligne de total. */
    @Query(value = """
            SELECT COALESCE(c.name, 'Sans contrat') AS contractName,
                   COALESCE(SUM(i.total), 0)        AS total
            FROM invoices i
            LEFT JOIN contrats c ON c.id = i.contrat_id
            WHERE i.branch_id = :branchId AND i.deleted_at IS NULL
              AND i.status_invoice = 0
              AND i.created_at >= :debut AND i.created_at < :finExclusive
            GROUP BY c.name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> sumByContractInPeriod(
            @Param("branchId") UUID branchId,
            @Param("debut") LocalDateTime debut,
            @Param("finExclusive") LocalDateTime finExclusive);

    // Rapports — totaux par contrat (ventes du mois)
    @Query(value = """
            SELECT COALESCE(c.name, 'Sans contrat') as contractName, COALESCE(SUM(i.total), 0) as total
            FROM invoices i
            LEFT JOIN contrats c ON c.id = i.contrat_id
            WHERE i.branch_id = :branchId AND i.deleted_at IS NULL
              AND i.status_invoice = 0
              AND EXTRACT(MONTH FROM i.created_at) = :month
              AND EXTRACT(YEAR FROM i.created_at) = :year
            GROUP BY c.name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> sumByContractAndMonthYear(
            @Param("branchId") UUID branchId,
            @Param("month") int month,
            @Param("year") int year);

    // Liste — compteur par type (vente / avoir)
    /**
     * Compte les factures non réglées de la branche, pour le badge « Factures » du menu
     * — équivalent du helper Laravel {@code getnbrInvoicepending()}
     * ({@code Invoice::where('paid', 0)->count()}).
     *
     * @param branchId identifiant de la branche
     * @return nombre de factures impayées
     */
    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.branchId = :branchId AND i.paid = FALSE")
    long countUnpaidByBranchId(@Param("branchId") UUID branchId);

    @Query("""
            SELECT COUNT(i) FROM Invoice i
            WHERE i.branchId = :branchId AND i.statusInvoice = :statusInvoice
            """)
    long countByBranchIdAndStatusInvoice(
            @Param("branchId") UUID branchId,
            @Param("statusInvoice") int statusInvoice);

    /**
     * Vérifie l'existence d'une facture active (non soft-deleted, filtré par
     * {@code @SQLRestriction}) pour un bon d'examen donné et un statut donné.
     * Utilisé pour empêcher la création d'une seconde facture sur le même bon.
     */
    boolean existsByTestOrderIdAndStatusInvoice(UUID testOrderId, int statusInvoice);

    /**
     * Compte les factures d'une branche créées au cours d'une année civile donnée.
     * Utilisé pour générer le numéro séquentiel du code facture ({@code FAYYNNNN}).
     */
    @Query(value = """
            SELECT COUNT(*) FROM invoices
            WHERE branch_id = :branchId
              AND EXTRACT(YEAR FROM created_at) = :year
              AND deleted_at IS NULL
            """, nativeQuery = true)
    long countByBranchIdAndCreatedAtYear(
            @Param("branchId") UUID branchId,
            @Param("year") int year);

    // Rapports — agrégats mensuels (Facturés / Avoirs / CA) pour une année
    /**
     * Récapitulatif mensuel des factures — alimente le tableau « Liste des Factures »
     * de l'écran Rapports.
     *
     * <p>Formules reprises telles quelles de {@code InvoiceController::getInvoiceforDatatable}
     * de Laravel, qui regroupe sur <b>updated_at</b> (date de dernier mouvement, donc
     * d'encaissement) et non sur la date de création :</p>
     * <ul>
     *   <li>{@code facturated} : {@code sum(total)} de toutes les factures du mois,
     *       ventes comme avoirs, payées ou non ;</li>
     *   <li>{@code credits} : avoirs payés ({@code status_invoice = 1 AND paid}) ;</li>
     *   <li>{@code turnover} : ventes payées ({@code status_invoice = 0 AND paid}).</li>
     * </ul>
     * Les encaissements sont ensuite calculés par le service ({@code turnover - credits}).
     */
    @Query(value = """
            SELECT
                EXTRACT(MONTH FROM updated_at)::int as month,
                COALESCE(SUM(total), 0) as facturated,
                COALESCE(SUM(CASE WHEN status_invoice = 1 AND paid = true THEN total ELSE 0 END), 0) as credits,
                COALESCE(SUM(CASE WHEN status_invoice = 0 AND paid = true THEN total ELSE 0 END), 0) as turnover
            FROM invoices
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND EXTRACT(YEAR FROM updated_at) = :year
            GROUP BY EXTRACT(MONTH FROM updated_at)
            ORDER BY month
            """, nativeQuery = true)
    List<Object[]> findMonthlyStatsRaw(
            @Param("branchId") UUID branchId,
            @Param("year") int year);

    /**
     * L'avoir rattaché à une facture de vente, s'il existe.
     *
     * <p>Sert de garde à la création d'un avoir : une vente n'en porte qu'un, un
     * second serait un doublon comptable.</p>
     */
    Optional<Invoice> findFirstByReferenceId(UUID referenceId);
}

package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.dashboard.DashboardDto;
import com.labo.anapath.dashboard.DashboardProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** Dans l'agence donnée seulement : un identifiant d'ailleurs est introuvable. */
    Optional<Report> findByIdAndBranchId(UUID id, UUID branchId);

    Page<Report> findByBranchId(UUID branchId, Pageable pageable);

    /** Les comptes-rendus d'une discipline dans la branche. */
    Page<Report> findByBranchIdAndDiscipline(UUID branchId, Discipline discipline, Pageable pageable);

    Optional<Report> findByTestOrderId(UUID testOrderId);

    /**
     * Comptes-rendus validés dont le patient n'a jamais reçu l'avis de
     * disponibilité — ceux que la tâche de rappel doit traiter.
     *
     * <p>Seuls les {@code VALIDATED} sont repris : un compte-rendu déjà
     * {@code DELIVERED} a été retiré, prévenir son patient n'a plus d'objet.</p>
     *
     * <p><b>La borne de date n'est pas décorative.</b> Après une panne de
     * plusieurs jours, la requête sans borne remonterait un arriéré entier et
     * lancerait des centaines d'appels d'un coup. Trois jours couvrent le cas
     * réel — un week-end prolongé — et rien au-delà : passé ce délai, l'avis se
     * fait au comptoir.</p>
     *
     * <p>Sans filtre de discipline, à dessein : prévenir le patient que son
     * résultat est prêt vaut pour la biologie comme pour l'anatomie
     * pathologique.</p>
     *
     * @param depuis   date de validation la plus ancienne encore reprise
     * @param avant    date de validation la plus récente déjà due (maintenant moins le délai de rappel)
     * @param pageable plafond du lot ; le tri est porté par la requête
     */
    @Query("SELECT r FROM Report r "
            + "WHERE r.status = com.labo.anapath.report.ReportStatus.VALIDATED "
            + "AND r.patientNotifiedAt IS NULL "
            + "AND r.signatureDate >= :depuis "
            + "AND r.signatureDate <= :avant "
            + "ORDER BY r.signatureDate ASC")
    List<Report> findAvisPatientEnAttente(@Param("depuis") LocalDateTime depuis,
                                          @Param("avant") LocalDateTime avant, Pageable pageable);

    List<Report> findByTestOrder_IdIn(Collection<UUID> testOrderIds);

    /**
     * Retrouve le compte-rendu à partir du code de sa demande d'examen.
     *
     * <p>Pivot du parcours mobile : au comptoir, on tient un bon d'examen et on
     * en lit le code — saisi ou scanné. Le code porte une contrainte d'unicité,
     * la relation au compte-rendu est un 1-1, donc au plus un résultat.</p>
     *
     * <p>La comparaison est insensible à la casse mais reste <strong>exacte</strong>.
     * Le préfixe des codes est un paramètre du laboratoire, et V61 rappelle que
     * les codes déjà émis ne sont pas renommés quand il change : « 26-0003 » et
     * « ABCD26-0003 » peuvent coexister. Un rapprochement approximatif sur le
     * suffixe désignerait alors le mauvais dossier — inacceptable pour un acte
     * qui remet des résultats médicaux ou en valide un.</p>
     */
    Optional<Report> findByTestOrder_CodeIgnoreCase(String code);

    @Query(value = """
            SELECT r.* FROM reports r
            WHERE r.deleted_at IS NULL
              AND r.branch_id = :branchId
              AND (:month IS NULL OR EXTRACT(MONTH FROM r.signature_date) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM r.signature_date) = :year)
              AND (:doctorId IS NULL OR r.signatory1 = CAST(:doctorId AS uuid))
              AND r.discipline = :discipline
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM reports r
            WHERE r.deleted_at IS NULL
              AND r.branch_id = :branchId
              AND (:month IS NULL OR EXTRACT(MONTH FROM r.signature_date) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM r.signature_date) = :year)
              AND (:doctorId IS NULL OR r.signatory1 = CAST(:doctorId AS uuid))
              AND r.discipline = :discipline
            """,
            nativeQuery = true)
    Page<Report> findFiltered(
            @Param("branchId") UUID branchId,
            @Param("month") Integer month,
            @Param("year") Integer year,
            @Param("doctorId") UUID doctorId,
            @Param("discipline") String discipline,
            Pageable pageable);

    /**
     * Liste filtrée avec recherche libre.
     *
     * <p>Le mot-clé est confronté à tout ce qui permet de retrouver le dossier
     * d'un patient : code du compte-rendu, code de la demande, code du patient,
     * nom, prénom, téléphones, et le texte même du compte-rendu (macro, micro
     * et compléments). La reprise n'en couvrait que trois — code de la demande,
     * nom et prénom — là où Laravel cherchait aussi dans le code du
     * compte-rendu, le code du patient et le contenu.</p>
     *
     * <p>Le contenu est cherché dans {@code content}, non dans
     * {@code description} : la migration a rempli les deux à l'identique, mais
     * seule la première est écrite depuis la bascule. Interroger la seconde
     * laisserait échapper tous les comptes-rendus rédigés depuis.</p>
     *
     * <p>Un {@code LIKE '%…%'} sur des colonnes TEXT interdit tout index. À
     * l'échelle actuelle — de l'ordre de quatorze mille comptes-rendus, environ
     * trois mille de plus par an — le parcours séquentiel reste imperceptible.
     * Si la recherche venait à traîner, la réponse est un index GIN trigramme
     * ({@code pg_trgm}) sur les colonnes de texte, pas un retrait de champs.</p>
     */
    @Query(value = """
            SELECT r.* FROM reports r
            JOIN test_orders tor ON tor.id = r.test_order_id AND tor.deleted_at IS NULL
            JOIN patients   pat ON pat.id = tor.patient_id  AND pat.deleted_at IS NULL
            WHERE r.deleted_at IS NULL
              AND r.branch_id = :branchId
              AND (:month    IS NULL OR EXTRACT(MONTH FROM r.signature_date) = :month)
              AND (:year     IS NULL OR EXTRACT(YEAR  FROM r.signature_date) = :year)
              AND (:doctorId IS NULL OR r.signatory1 = CAST(:doctorId AS uuid))
              AND (:status   IS NULL OR r.status::text = :status)
              AND r.discipline = :discipline
              AND (:search   IS NULL OR (
                    unaccent(lower(coalesce(r.code,         ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(tor.code,       ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.code,       ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.telephone1, ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.telephone2, ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.content,      ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.content_micro,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.description_supplementaire,      ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.description_supplementaire_micro,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.firstname,'') || ' ' || coalesce(pat.lastname,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.lastname,'') || ' ' || coalesce(pat.firstname,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
              ))
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM reports r
            JOIN test_orders tor ON tor.id = r.test_order_id AND tor.deleted_at IS NULL
            JOIN patients   pat ON pat.id = tor.patient_id  AND pat.deleted_at IS NULL
            WHERE r.deleted_at IS NULL
              AND r.branch_id = :branchId
              AND (:month    IS NULL OR EXTRACT(MONTH FROM r.signature_date) = :month)
              AND (:year     IS NULL OR EXTRACT(YEAR  FROM r.signature_date) = :year)
              AND (:doctorId IS NULL OR r.signatory1 = CAST(:doctorId AS uuid))
              AND (:status   IS NULL OR r.status::text = :status)
              AND r.discipline = :discipline
              AND (:search   IS NULL OR (
                    unaccent(lower(coalesce(r.code,         ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(tor.code,       ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.code,       ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.telephone1, ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.telephone2, ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.content,      ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.content_micro,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.description_supplementaire,      ''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(r.description_supplementaire_micro,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.firstname,'') || ' ' || coalesce(pat.lastname,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
                 OR unaccent(lower(coalesce(pat.lastname,'') || ' ' || coalesce(pat.firstname,''))) LIKE unaccent(lower('%' || CAST(:search AS text) || '%'))
              ))
            """,
            nativeQuery = true)
    Page<Report> findFilteredWithSearch(
            @Param("branchId") UUID branchId,
            @Param("month") Integer month,
            @Param("year") Integer year,
            @Param("doctorId") UUID doctorId,
            @Param("status") String status,
            @Param("search") String search,
            @Param("discipline") String discipline,
            Pageable pageable);

    @Query(value = """
            SELECT
                SUM(CASE WHEN to2.title = 'Histologie'     THEN 1 ELSE 0 END) AS histologie,
                SUM(CASE WHEN to2.title = 'Immuno Externe' THEN 1 ELSE 0 END) AS immuno_externe,
                SUM(CASE WHEN to2.title = 'Immuno Interne' THEN 1 ELSE 0 END) AS immuno_interne,
                SUM(CASE WHEN to2.title = 'Cytologie'      THEN 1 ELSE 0 END) AS cytologie,
                COUNT(tor.id) AS total_general
            FROM test_orders tor
            JOIN type_orders to2 ON tor.type_order_id = to2.id
            WHERE tor.status IN ('VALIDATED','DELIVERED') AND tor.branch_id = :branchId
            AND (:month IS NULL OR EXTRACT(MONTH FROM tor.created_at) = :month)
            AND (:year  IS NULL OR EXTRACT(YEAR  FROM tor.created_at) = :year)
            """, nativeQuery = true)
    // Pas de filtre de discipline : la jointure interne sur type_orders et les
    // titres comptés (Histologie, Immuno, Cytologie) n'existent qu'en anatomie
    // pathologique.
    Object[] getExamenStats(
            @Param("branchId") UUID branchId,
            @Param("month") Integer month,
            @Param("year") Integer year);

    @Query(value = """
            SELECT
                SUM(CASE WHEN rep.status = 'DRAFT'     THEN 1 ELSE 0 END) AS attente,
                SUM(CASE WHEN rep.status IN ('VALIDATED','DELIVERED') THEN 1 ELSE 0 END) AS termine,
                SUM(CASE WHEN toad.test_order_id IS NOT NULL THEN 1 ELSE 0 END) AS affecte
            FROM reports rep
            JOIN test_orders tor ON tor.id = rep.test_order_id
            LEFT JOIN test_order_assignment_details toad ON toad.test_order_id = rep.test_order_id
            WHERE rep.branch_id = :branchId AND rep.deleted_at IS NULL
              AND rep.discipline = :discipline
            """, nativeQuery = true)
    Object[] getRapportStats(@Param("branchId") UUID branchId,
                             @Param("discipline") String discipline);

    @Query(value = """
            SELECT
                SUM(CASE WHEN is_called    = TRUE  THEN 1 ELSE 0 END) AS called,
                SUM(CASE WHEN is_called    = FALSE THEN 1 ELSE 0 END) AS not_called,
                SUM(CASE WHEN is_delivered = TRUE  THEN 1 ELSE 0 END) AS deliver,
                SUM(CASE WHEN is_delivered = FALSE THEN 1 ELSE 0 END) AS not_deliver
            FROM reports
            WHERE branch_id = :branchId AND deleted_at IS NULL
              AND discipline = :discipline
            """, nativeQuery = true)
    Object[] getPatientCalledStats(@Param("branchId") UUID branchId,
                                   @Param("discipline") String discipline);

    @Query(value = """
            SELECT DISTINCT EXTRACT(YEAR FROM created_at)::int AS y
            FROM test_orders
            WHERE branch_id = :branchId AND deleted_at IS NULL
            ORDER BY y DESC
            """, nativeQuery = true)
    List<Integer> findAvailableYears(@Param("branchId") UUID branchId);

    @Query(value = """
            SELECT COUNT(tpm.id)
            FROM test_pathology_macros tpm
            JOIN test_orders tor ON tpm.test_order_id = tor.id
            WHERE tor.branch_id = :branchId AND tpm.deleted_at IS NULL
            """, nativeQuery = true)
    Long countMacrosWithOrders(@Param("branchId") UUID branchId);

    @Query(value = """
            SELECT
                r.id::text as reportId,
                t.id::text as testOrderId,
                t.code as testOrderCode,
                ty.title as typeOrderTitle,
                p.firstname as patientFirstname,
                p.lastname as patientLastname,
                p.telephone1 as patientPhone,
                t.is_urgent as isUrgent,
                r.created_at as createdAt,
                r.status as reportStatus,
                CASE WHEN EXISTS (
                    SELECT 1 FROM test_pathology_macros m
                    WHERE m.test_order_id = t.id AND m.deleted_at IS NULL
                ) THEN true ELSE false END as hasMacro,
                u.id::text as assignedDoctorId,
                CASE WHEN u.id IS NOT NULL
                    THEN CONCAT(u.firstname, ' ', u.lastname)
                    ELSE NULL END as assignedDoctorName,
                r.is_called as isCalled,
                r.is_delivered as isDelivered,
                r.retriever_name as retrieverName,
                r.retriever_signature as retrieverSignature,
                r.delivery_date as deliveryDate,
                ap.issue as rappelIssue,
                ap.attempts as rappelTentatives,
                ap.listened_pct as rappelPctEcoute,
                ap.created_at as rappelDate
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN type_orders ty ON t.type_order_id = ty.id
            LEFT JOIN LATERAL (
                SELECT a.issue, a.attempts, a.listened_pct, a.created_at
                FROM appel_by_reports a
                WHERE a.report_id = r.id AND a.issue IS NOT NULL AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC
                LIMIT 1
            ) ap ON true
            LEFT JOIN LATERAL (
                SELECT a2.user_id, a2.created_at
                FROM test_order_assignment_details d2
                JOIN test_order_assignments a2 ON a2.id = d2.test_order_assignment_id
                WHERE d2.test_order_id = t.id AND a2.deleted_at IS NULL
                ORDER BY a2.created_at DESC
                LIMIT 1
            ) latest_a ON true
            LEFT JOIN users u ON latest_a.user_id = u.id
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (:search IS NULL OR :search = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%'))))
              AND (:typeOrderId IS NULL OR t.type_order_id = CAST(:typeOrderId AS uuid))
              AND (:dateBegin IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (:dateEnd IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND (:isUrgent IS NULL OR t.is_urgent = :isUrgent)
              AND (:statusFilter IS NULL OR
                   (:statusFilter = 1 AND r.is_delivered = true) OR
                   (:statusFilter = 2 AND r.is_called = true) OR
                   (:statusFilter = 3 AND r.status = 'DRAFT') OR
                   (:statusFilter = 4 AND r.status IN ('VALIDATED','DELIVERED')) OR
                   (:statusFilter = 5 AND r.is_delivered = false))
              AND (:isLate IS NULL OR (r.status = 'DRAFT' AND DATE(r.created_at) <= CURRENT_DATE - 21))
              AND (:rappel IS NULL
                   OR (:rappel = 'A_RAPPELER'
                       AND ap.issue IN ('PARTIEL', 'SANS_REPONSE', 'INJOIGNABLE', 'SANS_NUMERO')
                       AND COALESCE(r.is_called, false) = false
                       AND COALESCE(r.is_delivered, false) = false)
                   OR ap.issue = :rappel)
              AND r.discipline = :discipline
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN LATERAL (
                SELECT a.issue, a.attempts, a.listened_pct, a.created_at
                FROM appel_by_reports a
                WHERE a.report_id = r.id AND a.issue IS NOT NULL AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC
                LIMIT 1
            ) ap ON true
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (:search IS NULL OR :search = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%'))))
              AND (:typeOrderId IS NULL OR t.type_order_id = CAST(:typeOrderId AS uuid))
              AND (:dateBegin IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (:dateEnd IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND (:isUrgent IS NULL OR t.is_urgent = :isUrgent)
              AND (:statusFilter IS NULL OR
                   (:statusFilter = 1 AND r.is_delivered = true) OR
                   (:statusFilter = 2 AND r.is_called = true) OR
                   (:statusFilter = 3 AND r.status = 'DRAFT') OR
                   (:statusFilter = 4 AND r.status IN ('VALIDATED','DELIVERED')) OR
                   (:statusFilter = 5 AND r.is_delivered = false))
              AND (:isLate IS NULL OR (r.status = 'DRAFT' AND DATE(r.created_at) <= CURRENT_DATE - 21))
              AND (:rappel IS NULL
                   OR (:rappel = 'A_RAPPELER'
                       AND ap.issue IN ('PARTIEL', 'SANS_REPONSE', 'INJOIGNABLE', 'SANS_NUMERO')
                       AND COALESCE(r.is_called, false) = false
                       AND COALESCE(r.is_delivered, false) = false)
                   OR ap.issue = :rappel)
              AND r.discipline = :discipline
            """,
            nativeQuery = true)
    Page<ReportSuiviProjection> findSuiviRows(
            @Param("branchId") UUID branchId,
            @Param("search") String search,
            @Param("typeOrderId") String typeOrderId,
            @Param("dateBegin") String dateBegin,
            @Param("dateEnd") String dateEnd,
            @Param("isUrgent") Boolean isUrgent,
            @Param("statusFilter") Integer statusFilter,
            @Param("isLate") Boolean isLate,
            @Param("rappel") String rappel,
            @Param("discipline") String discipline,
            Pageable pageable);

    @Query(value = """
            SELECT
                r.id::text as reportId,
                r.code as codeReport,
                t.id::text as testOrderId,
                t.code as codeExamen,
                COALESCE(ty.title, '') as typeExamen,
                COALESCE(c.name, '') as contractName,
                p.id::text as patientId,
                p.firstname as patientFirstname,
                p.lastname as patientLastname,
                d.id::text as doctorId,
                COALESCE(d.name, '') as doctorName,
                h.id::text as hospitalId,
                COALESCE(h.name, '') as hospitalName,
                COALESCE(t.reference_hopital, '') as referenceHospital,
                r.created_at as dateCreation,
                t.is_urgent as isUrgent,
                r.discipline as discipline
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN type_orders ty ON t.type_order_id = ty.id
            LEFT JOIN contrats c ON t.contrat_id = c.id
            LEFT JOIN doctors d ON t.doctor_id = d.id
            LEFT JOIN hospitals h ON t.hospital_id = h.id
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (CAST(:typeOrderIds AS text) IS NULL OR t.type_order_id::text = ANY(string_to_array(CAST(:typeOrderIds AS text), ',')))
              AND (CAST(:contratIds AS text) IS NULL OR t.contrat_id::text = ANY(string_to_array(CAST(:contratIds AS text), ',')))
              AND (CAST(:patientIds AS text) IS NULL OR t.patient_id::text = ANY(string_to_array(CAST(:patientIds AS text), ',')))
              AND (CAST(:doctorIds AS text) IS NULL OR t.doctor_id::text = ANY(string_to_array(CAST(:doctorIds AS text), ',')))
              AND (CAST(:hospitalIds AS text) IS NULL OR t.hospital_id::text = ANY(string_to_array(CAST(:hospitalIds AS text), ',')))
              AND (CAST(:referenceHospital AS text) IS NULL OR CAST(:referenceHospital AS text) = ''
                   OR LOWER(COALESCE(t.reference_hopital, '')) LIKE LOWER(CONCAT('%', CAST(:referenceHospital AS text), '%')))
              AND (CAST(:dateBegin AS text) IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (CAST(:dateEnd AS text) IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND (CAST(:content AS text) IS NULL OR CAST(:content AS text) = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%'))))
              AND (:isUrgent IS NULL OR t.is_urgent = :isUrgent)
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN type_orders ty ON t.type_order_id = ty.id
            LEFT JOIN contrats c ON t.contrat_id = c.id
            LEFT JOIN doctors d ON t.doctor_id = d.id
            LEFT JOIN hospitals h ON t.hospital_id = h.id
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (CAST(:typeOrderIds AS text) IS NULL OR t.type_order_id::text = ANY(string_to_array(CAST(:typeOrderIds AS text), ',')))
              AND (CAST(:contratIds AS text) IS NULL OR t.contrat_id::text = ANY(string_to_array(CAST(:contratIds AS text), ',')))
              AND (CAST(:patientIds AS text) IS NULL OR t.patient_id::text = ANY(string_to_array(CAST(:patientIds AS text), ',')))
              AND (CAST(:doctorIds AS text) IS NULL OR t.doctor_id::text = ANY(string_to_array(CAST(:doctorIds AS text), ',')))
              AND (CAST(:hospitalIds AS text) IS NULL OR t.hospital_id::text = ANY(string_to_array(CAST(:hospitalIds AS text), ',')))
              AND (CAST(:referenceHospital AS text) IS NULL OR CAST(:referenceHospital AS text) = ''
                   OR LOWER(COALESCE(t.reference_hopital, '')) LIKE LOWER(CONCAT('%', CAST(:referenceHospital AS text), '%')))
              AND (CAST(:dateBegin AS text) IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (CAST(:dateEnd AS text) IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND (CAST(:content AS text) IS NULL OR CAST(:content AS text) = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, '')))) LIKE unaccent(LOWER(CONCAT('%', CAST(:content AS text), '%'))))
              AND (:isUrgent IS NULL OR t.is_urgent = :isUrgent)
            """,
            nativeQuery = true)
    Page<ReportGlobalSearchProjection> globalSearch(
            @Param("branchId") UUID branchId,
            @Param("typeOrderIds") String typeOrderIdsCsv,
            @Param("contratIds") String contratIdsCsv,
            @Param("patientIds") String patientIdsCsv,
            @Param("doctorIds") String doctorIdsCsv,
            @Param("hospitalIds") String hospitalIdsCsv,
            @Param("referenceHospital") String referenceHospital,
            @Param("dateBegin") String dateBegin,
            @Param("dateEnd") String dateEnd,
            @Param("content") String content,
            @Param("isUrgent") Boolean isUrgent,
            Pageable pageable);

    @Query(value = """
            SELECT
                r.id::text as id,
                r.code as reportCode,
                t.id::text as testOrderId,
                t.code as testOrderCode,
                p.id::text as patientId,
                p.code as patientCode,
                p.firstname as patientFirstname,
                p.lastname as patientLastname,
                p.telephone1 as patientPhone,
                COALESCE(ty.title, '') as typeOrderTitle,
                r.status as status,
                r.is_delivered as isDelivered,
                r.is_called as isCalled,
                r.signature_date as signatureDate,
                r.created_at as createdAt
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN type_orders ty ON t.type_order_id = ty.id
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (CAST(:search AS text) IS NULL OR CAST(:search AS text) = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   -- Nom et prénom dans un seul champ, quel que soit l'ordre saisi :
                   -- « AHOSSI Jean » comme « Jean AHOSSI ». Les deux colonnes seules
                   -- ne suffisent pas — aucune ne contient l'expression entière.
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, ''))))
                        LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, ''))))
                        LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%'))))
              AND (CAST(:statusFilter AS text) IS NULL OR r.status = CAST(:statusFilter AS text))
              AND (CAST(:dateBegin AS text) IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (CAST(:dateEnd AS text) IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND r.discipline = :discipline
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            WHERE r.branch_id = :branchId
              AND r.deleted_at IS NULL
              AND (CAST(:search AS text) IS NULL OR CAST(:search AS text) = ''
                   OR unaccent(LOWER(COALESCE(r.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(t.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.code, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone1, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(p.telephone2, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.content_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(COALESCE(r.description_supplementaire_micro, ''))) LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   -- Nom et prénom dans un seul champ, quel que soit l'ordre saisi :
                   -- « AHOSSI Jean » comme « Jean AHOSSI ». Les deux colonnes seules
                   -- ne suffisent pas — aucune ne contient l'expression entière.
                   OR unaccent(LOWER(CONCAT(COALESCE(p.firstname, ''), ' ', COALESCE(p.lastname, ''))))
                        LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR unaccent(LOWER(CONCAT(COALESCE(p.lastname, ''), ' ', COALESCE(p.firstname, ''))))
                        LIKE unaccent(LOWER(CONCAT('%', CAST(:search AS text), '%'))))
              AND (CAST(:statusFilter AS text) IS NULL OR r.status = CAST(:statusFilter AS text))
              AND (CAST(:dateBegin AS text) IS NULL OR DATE(r.created_at) >= CAST(:dateBegin AS date))
              AND (CAST(:dateEnd AS text) IS NULL OR DATE(r.created_at) <= CAST(:dateEnd AS date))
              AND r.discipline = :discipline
            """,
            nativeQuery = true)
    Page<ReportListProjection> findListRows(
            @Param("branchId") UUID branchId,
            @Param("search") String search,
            @Param("statusFilter") String statusFilter,
            @Param("dateBegin") String dateBegin,
            @Param("dateEnd") String dateEnd,
            @Param("discipline") String discipline,
            Pageable pageable);

    @Query(value = """
            SELECT
                COUNT(*) as totalReports,
                SUM(CASE WHEN a.date IS NOT NULL
                         AND r.signature_date IS NOT NULL
                         AND (r.signature_date::date - a.date) <= 11 THEN 1 ELSE 0 END) as withinDeadline,
                SUM(CASE WHEN a.date IS NOT NULL
                         AND r.signature_date IS NOT NULL
                         AND (r.signature_date::date - a.date) > 11 THEN 1 ELSE 0 END) as beyondDeadline
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            LEFT JOIN test_order_assignment_details d ON d.test_order_id = t.id
            LEFT JOIN test_order_assignments a ON a.id = d.test_order_assignment_id AND a.deleted_at IS NULL
            WHERE r.branch_id = :branchId
              AND r.status IN ('VALIDATED','DELIVERED')
              AND r.deleted_at IS NULL
              AND (:doctorId IS NULL OR a.user_id = CAST(:doctorId AS uuid))
              AND (:month IS NULL OR EXTRACT(MONTH FROM r.signature_date) = :month)
              AND (:year IS NULL OR EXTRACT(YEAR FROM r.signature_date) = :year)
              AND r.discipline = :discipline
            """, nativeQuery = true)
    java.util.Map<String, Object> getReportPerformanceStats(
            @Param("branchId") UUID branchId,
            @Param("doctorId") String doctorId,
            @Param("month") Integer month,
            @Param("year") Integer year,
            @Param("discipline") String discipline);

    // Dashboard — comptages par statut de livraison, sur la période en cours
    /**
     * Les comptes rendus remis, ou non, établis depuis {@code depuis}.
     *
     * <p>Borné comme les autres indicateurs de charge : « non remis » comptait
     * tout l'historique, et un chiffre qui ne redescend jamais cesse d'être
     * regardé.</p>
     */
    @Query("""
            SELECT COUNT(r) FROM Report r
            WHERE r.branchId = :branchId
              AND r.isDelivered = :isDelivered
              AND r.createdAt >= :depuis
              AND r.discipline = :discipline
            """)
    long countByBranchIdAndIsDelivered(@Param("branchId") UUID branchId,
                                       @Param("isDelivered") boolean isDelivered,
                                       @Param("depuis") java.time.LocalDateTime depuis,
                                       @Param("discipline") Discipline discipline);

    /**
     * Compte les comptes rendus de la branche dont le statut fait partie de la liste,
     * en ne retenant que ceux rattachés à un bon d'examen.
     *
     * <p>Reprend le {@code $totalByStatus} de Laravel
     * ({@code TestOrder::join('reports')->groupBy('reports.status')}) : le donut
     * « STATUT D'EXAMENS » oppose les comptes rendus terminés (VALIDATED, DELIVERED)
     * à ceux encore en attente (DRAFT, PENDING_REVIEW).</p>
     *
     * @param branchId identifiant de la branche
     * @param statuses statuts à compter
     * @return nombre de comptes rendus correspondants
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(r) FROM Report r
            WHERE r.branchId = :branchId
              AND r.testOrder IS NOT NULL
              AND r.status IN :statuses
              AND r.discipline = :discipline
            """)
    long countByBranchIdAndStatusIn(
            @org.springframework.data.repository.query.Param("branchId") UUID branchId,
            @org.springframework.data.repository.query.Param("statuses")
            java.util.List<com.labo.anapath.report.ReportStatus> statuses,
            @Param("discipline") Discipline discipline);

    // Dashboard — rapports du jour
    @Query(value = """
            SELECT r.id::text as id, r.test_order_id::text as testOrderId, t.code as code,
                   p.lastname as patientLastname, p.firstname as patientFirstname,
                   r.created_at::text as createdAt, r.status as status,
                   r.is_delivered as isDeliver, i.id::text as invoiceId
            FROM reports r
            JOIN test_orders t ON r.test_order_id = t.id
            JOIN patients p ON t.patient_id = p.id
            LEFT JOIN invoices i ON i.test_order_id = t.id AND i.deleted_at IS NULL
            WHERE t.branch_id = :branchId AND DATE(r.updated_at) = :today
              AND r.deleted_at IS NULL AND t.deleted_at IS NULL
              AND r.discipline = :discipline
            ORDER BY r.updated_at DESC
            """, nativeQuery = true)
    List<DashboardProjection.ReportToday> findReportsTodayByBranchId(@Param("branchId") UUID branchId,
                                                               @Param("today") LocalDate today,
                                                               @Param("discipline") String discipline);
}

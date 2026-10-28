package com.labo.anapath.report;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppelByReportRepository extends JpaRepository<AppelByReport, UUID> {

    /** Le dernier appel d'un compte rendu : un compte rendu peut être rappelé plusieurs fois. */
    Optional<AppelByReport> findFirstByReportIdOrderByCreatedAtDesc(UUID reportId);

    /** Appels encore en file ou en cours chez FluidChat, à relire par la tâche de suivi. */
    List<AppelByReport> findByIssueAndCreatedAtAfterOrderByCreatedAtAsc(
            IssueRappel issue, LocalDateTime depuis, Pageable pageable);

    /**
     * Rappels par issue sur une période, pour une agence : seul le dernier appel
     * de chaque compte rendu compte, pour qu'un patient rappelé à la main ne
     * pèse pas deux fois.
     *
     * @return couples {@code [issue, nombre]}
     */
    @Query(value = """
            SELECT a.issue, COUNT(*)
            FROM appel_by_reports a
            WHERE a.branch_id = :branchId
              AND a.issue IS NOT NULL
              AND a.deleted_at IS NULL
              AND a.created_at >= :debut AND a.created_at < :fin
              AND NOT EXISTS (SELECT 1 FROM appel_by_reports n
                              WHERE n.report_id = a.report_id AND n.issue IS NOT NULL
                                AND n.deleted_at IS NULL AND n.created_at > a.created_at)
            GROUP BY a.issue
            """, nativeQuery = true)
    List<Object[]> compterParIssue(@Param("branchId") UUID branchId,
                                   @Param("debut") LocalDateTime debut,
                                   @Param("fin") LocalDateTime fin);

    /**
     * Patients à rappeler par le secrétariat : dernier appel non abouti, patient
     * ni marqué informé, ni venu chercher son résultat. Sans borne de date —
     * c'est le bandeau : un patient non prévenu le reste tant que personne n'agit.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM appel_by_reports a
            JOIN reports r ON r.id = a.report_id
            WHERE a.branch_id = :branchId
              AND a.issue IN ('PARTIEL', 'SANS_REPONSE', 'INJOIGNABLE', 'SANS_NUMERO')
              AND a.deleted_at IS NULL
              AND r.deleted_at IS NULL
              AND COALESCE(r.is_called, false) = false
              AND COALESCE(r.is_delivered, false) = false
              AND NOT EXISTS (SELECT 1 FROM appel_by_reports n
                              WHERE n.report_id = a.report_id AND n.issue IS NOT NULL
                                AND n.deleted_at IS NULL AND n.created_at > a.created_at)
            """, nativeQuery = true)
    long compterARappeler(@Param("branchId") UUID branchId);
}

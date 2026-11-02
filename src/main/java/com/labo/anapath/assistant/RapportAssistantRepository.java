package com.labo.anapath.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface RapportAssistantRepository extends JpaRepository<RapportAssistant, UUID> {

    List<RapportAssistant> findTop12ByBranchIdOrderByCreatedAtDesc(UUID branchId);

    /** La synthèse consomme des jetons : ils comptent dans le quota du mois. */
    @Query("SELECT COALESCE(SUM(r.jetons), 0) FROM RapportAssistant r WHERE r.createdAt >= :depuis")
    long jetonsDepuis(@Param("depuis") LocalDateTime depuis);

    @Modifying
    @Query("DELETE FROM RapportAssistant r WHERE r.createdAt < :avant")
    int purger(@Param("avant") LocalDateTime avant);

    // --- Statistiques d'une agence sur une période (requêtes natives, lecture seule) ---

    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE m.role = 'user' AND jsonb_array_length(m.contenu) > 0),
                   COUNT(DISTINCT c.user_id),
                   COUNT(*) FILTER (WHERE m.avis = 1),
                   COUNT(*) FILTER (WHERE m.avis = -1),
                   COALESCE(SUM(m.jetons_entree + m.jetons_sortie), 0)
            FROM assistant_messages m
            JOIN assistant_conversations c ON c.id = m.conversation_id
            WHERE c.branch_id = :agence AND m.created_at >= :depuis AND m.created_at < :jusqua
            """, nativeQuery = true)
    List<Object[]> usage(@Param("agence") UUID agence, @Param("depuis") LocalDateTime depuis,
                         @Param("jusqua") LocalDateTime jusqua);

    @Query(value = """
            SELECT outil, statut, COUNT(*) FROM assistant_appels_outils
            WHERE branch_id = :agence AND created_at >= :depuis AND created_at < :jusqua
            GROUP BY outil, statut ORDER BY outil, statut
            """, nativeQuery = true)
    List<Object[]> appelsParOutil(@Param("agence") UUID agence, @Param("depuis") LocalDateTime depuis,
                                  @Param("jusqua") LocalDateTime jusqua);

    /** Les 👎 de la période : la question qui précédait (forme envoyée, masquée) et le commentaire. */
    @Query(value = """
            SELECT (SELECT q.envoye FROM assistant_messages q
                    WHERE q.conversation_id = m.conversation_id AND q.role = 'user' AND q.seq < m.seq
                      AND jsonb_array_length(q.contenu) > 0
                    ORDER BY q.seq DESC LIMIT 1),
                   m.avis_commentaire
            FROM assistant_messages m
            JOIN assistant_conversations c ON c.id = m.conversation_id
            WHERE c.branch_id = :agence AND m.avis = -1 AND m.created_at >= :depuis AND m.created_at < :jusqua
            ORDER BY m.created_at DESC LIMIT 100
            """, nativeQuery = true)
    List<Object[]> avisNegatifs(@Param("agence") UUID agence, @Param("depuis") LocalDateTime depuis,
                                @Param("jusqua") LocalDateTime jusqua);

    @Query(value = "SELECT DISTINCT branch_id FROM assistant_conversations WHERE updated_at >= :depuis",
            nativeQuery = true)
    List<UUID> agencesActives(@Param("depuis") LocalDateTime depuis);
}

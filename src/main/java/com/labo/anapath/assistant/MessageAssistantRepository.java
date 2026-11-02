package com.labo.anapath.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageAssistantRepository extends JpaRepository<MessageAssistant, UUID> {

    List<MessageAssistant> findByConversationIdOrderBySeq(UUID conversationId);

    /** La consommation depuis une date, toutes agences : le quota est celui du laboratoire. */
    @Query("SELECT COALESCE(SUM(m.jetonsEntree + m.jetonsSortie), 0) FROM MessageAssistant m WHERE m.createdAt >= :depuis")
    long jetonsDepuis(@Param("depuis") LocalDateTime depuis);

    /** Avant les conversations : la suite de tests n'a pas la clé étrangère en cascade de la migration. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            DELETE FROM assistant_messages WHERE conversation_id IN (
                SELECT id FROM assistant_conversations WHERE updated_at < :avant)
            """, nativeQuery = true)
    int purgerAvant(@Param("avant") LocalDateTime avant);
}

package com.labo.anapath.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndUserIdAndBranchIdAndDeletedAtIsNull(UUID id, UUID userId, UUID branchId);

    List<Conversation> findTop50ByUserIdAndBranchIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID userId, UUID branchId);

    /** Prend l'appel en attente ; 1 si c'est nous, 0 s'il a déjà été tranché. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.appelEnAttenteId = NULL WHERE c.id = :id AND c.appelEnAttenteId = :appel")
    int prendreLAppel(@Param("id") UUID id, @Param("appel") String appel);

    @Modifying
    @Query(value = "DELETE FROM assistant_conversations WHERE updated_at < :avant", nativeQuery = true)
    int purger(@Param("avant") LocalDateTime avant);
}

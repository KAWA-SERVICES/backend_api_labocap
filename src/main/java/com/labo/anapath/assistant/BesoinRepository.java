package com.labo.anapath.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface BesoinRepository extends JpaRepository<Besoin, UUID> {

    List<Besoin> findTop200ByBranchIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(UUID branchId, LocalDateTime depuis);

    @Modifying
    @Query("DELETE FROM Besoin b WHERE b.createdAt < :avant")
    int purger(@Param("avant") LocalDateTime avant);
}

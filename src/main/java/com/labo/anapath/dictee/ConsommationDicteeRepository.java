package com.labo.anapath.dictee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ConsommationDicteeRepository extends JpaRepository<ConsommationDictee, UUID> {

    /** Secondes consommées par une agence depuis {@code depuis} (0 si rien). */
    @Query("SELECT COALESCE(SUM(c.secondes), 0) FROM ConsommationDictee c "
            + "WHERE c.branchId = :branchId AND c.createdAt >= :depuis")
    BigDecimal secondesDepuis(@Param("branchId") UUID branchId, @Param("depuis") LocalDateTime depuis);
}

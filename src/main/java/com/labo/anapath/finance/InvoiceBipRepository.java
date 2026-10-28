package com.labo.anapath.finance;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Marque du bip de la facture, écrite seule : voir {@link Invoice#getBipSentAt()}.
 */
public interface InvoiceBipRepository extends Repository<Invoice, UUID> {

    @Modifying
    @Query(value = "UPDATE invoices SET bip_sent_at = :le WHERE id = :id", nativeQuery = true)
    void marquerBip(@Param("id") UUID id, @Param("le") LocalDateTime le);
}

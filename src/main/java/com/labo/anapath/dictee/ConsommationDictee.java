package com.labo.anapath.dictee;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un segment de dictée transcrit : qui, quelle agence, combien de secondes.
 *
 * <p>Ni l'audio ni le texte : seule la durée, facturée par le fournisseur,
 * est gardée pour le quota et la facturation.</p>
 */
@Entity
@Table(name = "consommation_dictee")
@Getter
@NoArgsConstructor
public class ConsommationDictee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "secondes", nullable = false, precision = 10, scale = 2)
    private BigDecimal secondes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ConsommationDictee(UUID branchId, UUID userId, BigDecimal secondes) {
        this.branchId = branchId;
        this.userId = userId;
        this.secondes = secondes;
    }
}

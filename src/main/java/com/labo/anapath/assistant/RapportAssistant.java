package com.labo.anapath.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Le rapport d'amélioration d'une agence sur une période (voir {@link RapportAssistantService}). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_rapports")
public class RapportAssistant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "debut", nullable = false)
    private LocalDate debut;

    @Column(name = "fin", nullable = false)
    private LocalDate fin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statistiques", nullable = false, columnDefinition = "jsonb")
    private String statistiques;

    @Column(name = "synthese", columnDefinition = "TEXT")
    private String synthese;

    @Column(name = "jetons", nullable = false)
    private int jetons;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

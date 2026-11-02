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

import java.time.LocalDateTime;
import java.util.UUID;

/** Une demande que l'assistant n'a pas su satisfaire, signalée par lui (outil {@code signaler_besoin}). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_besoins")
public class Besoin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Reformulée par le modèle, sous forme masquée. */
    @Column(name = "demande", nullable = false, length = 500)
    private String demande;

    @Column(name = "raison", nullable = false, length = 30)
    private String raison;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

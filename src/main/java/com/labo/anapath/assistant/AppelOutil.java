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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * La trace de chaque appel d'outil, réussi, échoué ou refusé.
 *
 * <p>Conservée au-delà de la purge des conversations : c'est elle qui dit,
 * après coup, ce que l'assistant a lu ou fait au nom de qui.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_appels_outils")
public class AppelOutil {

    public enum Statut { SUCCES, ECHEC, REFUSE, EN_ATTENTE, CONFIRME, REJETE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "outil", nullable = false, length = 100)
    private String outil;

    @Column(name = "categorie", nullable = false, length = 20)
    private String categorie;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb")
    private String arguments;

    @Column(name = "statut", nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Statut statut;

    @Column(name = "erreur", columnDefinition = "TEXT")
    private String erreur;

    @Column(name = "duree_ms", nullable = false)
    private int dureeMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

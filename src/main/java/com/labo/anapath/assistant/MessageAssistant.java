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
 * Un message de conversation, sous ses deux formes.
 *
 * <p>{@code contenu} est en clair : c'est ce que l'écran relit. {@code envoye}
 * est la forme masquée telle qu'elle est partie chez le fournisseur, figée :
 * l'historique renvoyé à chaque tour doit rester identique octet pour octet,
 * sans quoi le fournisseur écarte la réflexion du modèle (ou refuse la
 * requête). Les deux sont des tableaux de blocs neutres (voir {@code FournisseurLlm}).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_messages")
public class MessageAssistant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "seq", nullable = false)
    private int seq;

    /** {@code user}, {@code assistant} ou {@code tool}. */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contenu", nullable = false, columnDefinition = "jsonb")
    private String contenu;

    /**
     * En {@code TEXT} et non en {@code jsonb} : {@code jsonb} réordonne les clés,
     * et l'historique ne repartirait plus octet pour octet.
     */
    @Column(name = "envoye", nullable = false, columnDefinition = "TEXT")
    private String envoye;

    @Column(name = "jetons_entree", nullable = false)
    private int jetonsEntree;

    @Column(name = "jetons_sortie", nullable = false)
    private int jetonsSortie;

    /** 1 = 👍, -1 = 👎, null = pas d'avis. */
    @Column(name = "avis")
    private Short avis;

    /** Masqué comme la forme envoyée : il part dans le rapport d'amélioration. */
    @Column(name = "avis_commentaire", length = 500)
    private String avisCommentaire;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

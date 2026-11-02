package com.labo.anapath.assistant;

import com.labo.anapath.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Une conversation d'un utilisateur avec l'assistant, dans une agence.
 *
 * <p>Toujours chargée par {@code (id, userId, branchId)} : une conversation
 * n'est visible que de son auteur, et dans l'agence où elle a été ouverte.</p>
 *
 * <p>Le fournisseur est figé à l'ouverture : l'historique contient des blocs
 * propres à ce fournisseur, qu'un autre ne saurait pas relire.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_conversations")
public class Conversation extends AuditableEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "titre", length = 200)
    private String titre;

    @Column(name = "fournisseur", nullable = false, length = 20)
    private String fournisseur;

    /**
     * L'appel d'écriture qui attend la confirmation de l'utilisateur, ou
     * {@code null}. Remis à {@code null} par une mise à jour conditionnelle :
     * une double confirmation ne l'exécute qu'une fois.
     */
    @Column(name = "appel_en_attente_id", length = 100)
    private String appelEnAttenteId;
}

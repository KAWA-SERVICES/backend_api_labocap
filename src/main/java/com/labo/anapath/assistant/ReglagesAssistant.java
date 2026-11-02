package com.labo.anapath.assistant;

import com.labo.anapath.common.security.SecretChiffreConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Les réglages de l'assistant, un seul jeu pour le laboratoire.
 *
 * <p>Un laboratoire = un déploiement : la clé et le quota valent pour toutes
 * ses agences. Pas dans {@code settings}, qui est par agence et lisible sous
 * {@code view-settings} : la clé y fuirait.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_reglages")
public class ReglagesAssistant {

    public static final short ID = 1;

    @Id
    private Short id = ID;

    @Column(name = "actif", nullable = false)
    private boolean actif;

    @Column(name = "fournisseur", nullable = false, length = 20)
    private String fournisseur = "ANTHROPIC";

    @Column(name = "modele", nullable = false, length = 100)
    private String modele = "claude-opus-5-5";

    /** Chiffrée au repos ; jamais renvoyée par l'API. */
    @Convert(converter = SecretChiffreConverter.class)
    @Column(name = "cle_api", columnDefinition = "TEXT")
    private String cleApi;

    @Column(name = "caviardage", nullable = false)
    private boolean caviardage = true;

    /** {@code null} : sans plafond. */
    @Column(name = "quota_mensuel_jetons")
    private Long quotaMensuelJetons;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;
}

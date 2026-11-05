package com.labo.anapath.common.notification;

import com.labo.anapath.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Appel, bip ou SMS que FluidChat n'a pas reçu (panne, délai dépassé), à renvoyer
 * avec la même clé (V115). Supprimée dès que l'envoi aboutit.
 */
@Entity
@Table(name = "demandes_fluidchat")
@Getter
@Setter
@NoArgsConstructor
public class DemandeFluidChat extends AuditableEntity {

    public enum Type {
        /** Appel « votre résultat est disponible » ; cible : le compte rendu. */
        APPEL_RESULTAT,
        /** SMS du même avis ; cible : le compte rendu. */
        SMS_RESULTAT,
        /** Bip à la facture ; cible : la facture. */
        BIP_FACTURE,
        /** SMS de la facture téléchargeable ; cible : la facture. */
        SMS_FACTURE
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private Type type;

    @Column(name = "cible_id", nullable = false)
    private UUID cibleId;

    /** {@code idempotency_key} de l'appel ou du bip ; pour un SMS, l'identifiant d'où sort sa {@code reference}. */
    @Column(name = "cle", length = 64, nullable = false)
    private String cle;

    @Column(name = "tentatives", nullable = false)
    private int tentatives;

    @Column(name = "derniere_erreur", length = 255)
    private String derniereErreur;

    /** Renvoi arrêté : trois jours écoulés, ou refus de FluidChat. Nul tant que la demande attend. */
    @Column(name = "abandonnee_le")
    private LocalDateTime abandonneeLe;
}

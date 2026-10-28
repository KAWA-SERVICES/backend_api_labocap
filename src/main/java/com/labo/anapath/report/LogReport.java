package com.labo.anapath.report;

import com.labo.anapath.common.audit.AuditableEntity;
import com.labo.anapath.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entité de traçabilité : enregistre chaque action effectuée sur un compte-rendu.
 *
 * <p>La journalisation est une exigence réglementaire en anatomopathologie : toute modification,
 * validation ou livraison d'un CR doit être tracée avec l'identité de l'auteur et un horodatage.
 * Les entrées de ce journal sont immuables — elles ne doivent jamais être modifiées
 * ni supprimées après création.
 *
 * <p>Les entrées sont également créées lors de la validation d'un {@link com.labo.anapath.testorder.TestOrder}
 * (par {@code TestOrderServiceImpl}) pour tracer la création initiale du CR.
 */
@Entity
@Table(name = "log_reports")
@Getter
@Setter
@NoArgsConstructor
public class LogReport extends AuditableEntity {

    /** Compte-rendu concerné par cette entrée de journal. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /** Utilisateur ayant effectué l'action tracée ; nul pour une action automatique. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Code de l'action effectuée (ex. {@code "CREATE"}, {@code "VALIDATE"}, {@code "DELIVER"}). */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /** Description textuelle complémentaire de l'action (contexte, détails). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}

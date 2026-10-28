package com.labo.anapath.report;

import com.labo.anapath.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Un rappel automatique d'un patient : une ligne par appel (V108). */
@Entity
@Table(name = "appel_by_reports")
@Getter
@Setter
@NoArgsConstructor
public class AppelByReport extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /** Identifiant de l'appel chez FluidChat ; nul si aucun appel n'est parti (patient sans numéro). */
    @Column(name = "appel_id", length = 255)
    private String appelId;

    /** Statut FluidChat : queued, calling, answered, no_answer, failed, canceled. */
    @Column(name = "status", length = 20)
    private String status;

    /** Issue FluidChat de la dernière tentative : completed, partial, no_answer, busy, unreachable, failed. */
    @Column(name = "result", length = 20)
    private String result;

    @Column(name = "listened_pct")
    private Integer listenedPct;

    @Column(name = "attempts")
    private Integer attempts;

    /** Lecture métier de l'appel ; nulle pour les appels OurVoice d'avant V108. */
    @Enumerated(EnumType.STRING)
    @Column(name = "issue", length = 20)
    private IssueRappel issue;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}

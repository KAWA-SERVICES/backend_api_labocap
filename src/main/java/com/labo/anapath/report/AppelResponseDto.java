package com.labo.anapath.report;

import java.time.LocalDateTime;
import java.util.UUID;

/** Dernier appel d'un compte rendu ; issue et suite nulles pour un appel OurVoice d'avant V108. */
public record AppelResponseDto(UUID id, UUID reportId, String appelId, LocalDateTime createdAt,
                               IssueRappel issue, String result, Integer listenedPct, Integer attempts) {}

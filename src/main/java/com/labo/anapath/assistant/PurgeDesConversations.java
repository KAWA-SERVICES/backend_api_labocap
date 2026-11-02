package com.labo.anapath.assistant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Les conversations de l'assistant portent des données de santé en clair :
 * purgées après {@value AssistantService#RETENTION_JOURS} jours sans activité.
 * La trace des appels d'outils (qui a lu quoi) n'est pas concernée.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurgeDesConversations {

    private final AssistantService assistant;
    private final RapportAssistantService rapports;

    /** Chaque nuit à 3 h 45, heure du laboratoire. */
    @Scheduled(cron = "0 45 3 * * *", zone = "Africa/Porto-Novo")
    public void purger() {
        int n = assistant.purger();
        rapports.purger(java.time.LocalDateTime.now().minusDays(AssistantService.RETENTION_JOURS));
        if (n > 0) log.info("Purge de l'assistant : {} conversations de plus de {} jours", n, AssistantService.RETENTION_JOURS);
    }
}

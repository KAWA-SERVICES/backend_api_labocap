package com.labo.anapath.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Rappel automatique des patients dont le résultat est prêt.
 *
 * <p>Un compte rendu validé n'est pas annoncé tout de suite : le patient est
 * prévenu (SMS + appel FluidChat) {@link ReglagesDeRappel#delaiHeures() X heures}
 * après la validation, au premier passage de cette tâche qui tombe dans la plage
 * réglementaire (ARCE : du lundi au vendredi, 8h–18h par défaut). Hors de la
 * plage, la tâche ne fait rien — le SMS non plus ne part pas la nuit.</p>
 *
 * <p><b>Deux garde-fous.</b> La fenêtre de {@value #FENETRE_JOURS} jours (au-delà
 * du délai) empêche qu'un arriéré — panne, reprise de service — ne parte d'un
 * seul coup, et le lot de {@value #LOT_MAX} plafonne ce qu'une exécution peut
 * envoyer. Passé ces limites, l'avis se fait au comptoir : mieux vaut un patient
 * non rappelé qu'une campagne d'appels partie de travers.</p>
 *
 * <p>Une seconde tâche relit chez FluidChat l'issue des appels en cours : c'est
 * elle qui remplit l'historique des rappels et la liste « à rappeler ».</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RattrapageAvisPatientJob {

    /** Ancienneté maximale, au-delà du délai, d'une validation encore rappelée, en jours. */
    private static final int FENETRE_JOURS = 3;

    /** Nombre maximal d'avis envoyés, ou d'appels relus, par exécution. */
    private static final int LOT_MAX = 100;

    /** Au-delà, un appel encore « en cours » n'est plus relu : FluidChat a fini depuis longtemps. */
    private static final int SUIVI_JOURS = 7;

    private final ReportRepository reportRepository;
    private final NotificationService notificationService;
    private final ReglagesDeRappel reglagesDeRappel;
    private final AppelByReportRepository appelByReportRepository;

    /**
     * Toutes les 15 minutes par défaut ({@code REPORT_NOTIFY_RETRY_CRON}, « - »
     * désactive). Le fuseau est explicite : le conteneur de production tourne en UTC.
     */
    @Scheduled(cron = "${app.report.notify-retry.cron:0 */15 * * * *}", zone = "Africa/Porto-Novo")
    public void rappelerLesPatients() {
        if (!reglagesDeRappel.plage().contient(ZonedDateTime.now())) {
            return;
        }
        int envoyes = rattraperAvisEnAttente();
        if (envoyes > 0) {
            log.info("[scheduler] Rappel des patients : {} avis envoyé(s)", envoyes);
        }
    }

    /**
     * Envoie les avis dus et renvoie le nombre d'envois effectués.
     *
     * <p>Publique et distincte du déclencheur planifié, pour être appelable
     * depuis un test sans dépendre de l'heure qu'il est.</p>
     *
     * <p>Chaque compte-rendu est traité isolément : {@code notifyPatient} porte
     * sa propre transaction, et une erreur — FluidChat injoignable, passerelle
     * SMS en panne — ne doit pas priver d'avis les patients suivants du lot.</p>
     *
     * @return le nombre d'avis réellement partis
     */
    public int rattraperAvisEnAttente() {
        LocalDateTime avant = LocalDateTime.now().minusHours(reglagesDeRappel.delaiHeures());
        LocalDateTime depuis = avant.minusDays(FENETRE_JOURS);
        List<Report> dus = reportRepository.findAvisPatientEnAttente(depuis, avant, PageRequest.of(0, LOT_MAX));

        int envoyes = 0;
        for (Report report : dus) {
            try {
                // Sans utilisateur : l'avis n'est le geste de personne. La
                // journalisation l'accepte — voir NotificationServiceImpl#logAction.
                NotifyResponseDto resultat = notificationService.notifyPatient(report.getId(), null);
                if (!"NONE".equals(resultat.channel())) {
                    envoyes++;
                }
            } catch (RuntimeException e) {
                log.error("Rappel : avis du compte-rendu {} non envoyé : {}", report.getId(), e.getMessage());
            }
        }
        return envoyes;
    }

    /**
     * Relit chez FluidChat les appels en cours, toutes les 10 minutes par défaut
     * ({@code REPORT_CALL_FOLLOW_CRON}, « - » désactive).
     *
     * <p>Interroger plutôt qu'attendre le callback : les callbacks de FluidChat ne
     * sont pas signés, et sa documentation demande de relire l'appel avant d'agir.
     * Relire directement évite d'ouvrir une route publique pour rien.</p>
     */
    @Scheduled(cron = "${app.report.call-follow.cron:0 */10 * * * *}", zone = "Africa/Porto-Novo")
    public void suivreLesAppels() {
        List<AppelByReport> enCours = appelByReportRepository.findByIssueAndCreatedAtAfterOrderByCreatedAtAsc(
                IssueRappel.EN_COURS, LocalDateTime.now().minusDays(SUIVI_JOURS), PageRequest.of(0, LOT_MAX));
        for (AppelByReport appel : enCours) {
            try {
                notificationService.actualiser(appel);
            } catch (RuntimeException e) {
                log.warn("Suivi de l'appel {} impossible : {}", appel.getAppelId(), e.getMessage());
            }
        }
    }
}

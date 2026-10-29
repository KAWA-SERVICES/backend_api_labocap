package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.PhoneNumbers;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.common.notification.SmsTemplates;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    /**
     * Fuseau du laboratoire, celui dans lequel la plage d'appel a un sens.
     *
     * <p>Explicite, et non l'heure de la machine : le conteneur de production est
     * en UTC, si bien qu'une plage lue sur l'horloge du serveur décalerait tous
     * les appels d'une heure — et en ferait partir un à 19h, heure du patient.</p>
     */
    public static final ZoneId FUSEAU_LABORATOIRE = ZoneId.of("Africa/Porto-Novo");

    private final ReportRepository reportRepository;
    private final AppelByReportRepository appelByReportRepository;
    private final TestOrderRepository testOrderRepository;
    private final LogReportRepository logReportRepository;
    private final SmsTemplates smsTemplates;
    private final UserRepository userRepository;
    /** Appels vocaux : FluidChat, par le téléphone passerelle du laboratoire. */
    private final FluidChatClient fluidChatClient;
    private final ReglagesDeRappel reglagesDeRappel;
    /** SMS : passerelle FluidPay, qui achemine via OurVoice. */
    private final SmsSender smsSender;

    /**
     * Met en file chez FluidChat l'appel « votre résultat est disponible ».
     *
     * <p>La plage réglementaire part avec l'appel ({@code call_window}) : FluidChat
     * fait attendre l'appel et ses relances jusqu'à l'ouverture, y compris quand
     * un agent clique hors des heures permises. Chaque appel ajoute une ligne à
     * l'historique ; la tâche de suivi en relit l'issue.</p>
     */
    @Override
    @Transactional
    public CallResponseDto callPatient(UUID reportId, UUID userId) {
        // Global : appelé aussi hors requête (rappel planifié), sans agence courante.
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte-rendu", reportId));

        var patient = report.getTestOrder().getPatient();
        String to = numeroDuPatient(report);
        if (to == null) {
            throw new InvalidOperationException(
                    "Aucun numéro de téléphone pour ce patient : renseignez-le sur sa fiche.");
        }
        // Un appel déjà en file ou en cours : un second clic ne doit pas faire
        // sonner le patient deux fois (ni payer deux appels).
        if (appelByReportRepository.findFirstByReportIdOrderByCreatedAtDesc(reportId)
                .map(dernier -> dernier.getIssue() == IssueRappel.EN_COURS).orElse(false)) {
            throw new InvalidOperationException("Un appel est déjà programmé pour ce patient.");
        }
        String audio = reglagesDeRappel.audio(patient.getLangue());
        if (audio == null) {
            throw new InvalidOperationException(
                    "Lien audio de la langue du patient non renseigné (Paramètres › Rappel des patients).");
        }

        FluidChatClient.Appel lance = fluidChatClient.lancer("+" + to, audio,
                reglagesDeRappel.plage().versCallWindow(), report.getTestOrder().getCode());
        String appelId = String.valueOf(lance.id());

        report.getTestOrder().setStatusAppel(appelId);
        testOrderRepository.save(report.getTestOrder());

        AppelByReport appel = nouvelAppel(report);
        appel.setAppelId(appelId);
        appliquer(appel, lance);
        appelByReportRepository.save(appel);

        // L'avis est parti : la tâche de rappel n'a plus à s'en occuper.
        report.setPatientNotifiedAt(LocalDateTime.now());
        reportRepository.save(report);

        logAction(reportId, "Appel vocal lancé", userId, report.getBranchId());

        return new CallResponseDto(appelId, reportId, audio);
    }

    /**
     * Reporte sur une ligne d'historique ce que FluidChat dit de l'appel.
     *
     * <p>Package-private : la tâche de suivi s'en sert à chaque relecture.</p>
     */
    @Override
    @Transactional
    public void actualiser(AppelByReport appel) {
        // Rechargé dans cette transaction : la tâche de suivi passe une ligne lue
        // hors transaction, dont le compte rendu (paresseux) ne se chargerait plus.
        // Dans getAppelStatus, la ligne est déjà gérée : c'est la même instance.
        appel = appelByReportRepository.findById(appel.getId()).orElse(appel);
        appliquer(appel, fluidChatClient.lire(Long.parseLong(appel.getAppelId())));
        appelByReportRepository.save(appel);
        // Joint : le patient est informé, comme si le secrétariat l'avait marqué.
        Report report = appel.getReport();
        if (appel.getIssue() == IssueRappel.JOINT && report != null && !report.isCalled()) {
            report.setCalled(true);
            reportRepository.save(report);
        }
    }

    static void appliquer(AppelByReport appel, FluidChatClient.Appel lu) {
        appel.setStatus(lu.status());
        appel.setResult(lu.result());
        appel.setListenedPct(lu.listenedPct());
        appel.setAttempts(lu.attempts());
        appel.setIssue(IssueRappel.de(lu.status(), lu.result(), lu.listenedPct()));
        if (appel.getIssue() != IssueRappel.EN_COURS && appel.getFinishedAt() == null) {
            appel.setFinishedAt(LocalDateTime.now());
        }
    }

    @Override
    @Transactional
    public SmsResponseDto sendSms(UUID reportId, UUID userId) {
        // Global : appelé aussi hors requête (événement de validation, rattrapage planifié), sans agence courante.
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte-rendu", reportId));

        String to = numeroDuPatient(report);
        if (to == null) {
            throw new InvalidOperationException(
                    "Aucun numéro de téléphone pour ce patient : renseignez-le sur sa fiche.");
        }

        // Texte modifiable depuis Paramètres → Communication Mobile ; le message
        // livré prend le relais tant qu'aucun n'y a été saisi.
        //
        // Clé d'idempotence tirée au sort, et non l'identifiant du compte-rendu :
        // ce SMS se renvoie à la demande depuis l'écran de suivi, et une clé stable
        // ferait écarter la relance comme un doublon.
        //
        // Le texte suit la discipline : celui d'anatomie pathologique nomme le
        // cabinet d'anatomie pathologique, la biologie a le sien. Le chemin
        // d'anatomie pathologique est inchangé.
        String texte = report.getDiscipline() == Discipline.BIOLOGY
                ? smsTemplates.smsResultatBiologie(report.getBranchId())
                : smsTemplates.smsResultat(report.getBranchId());
        smsSender.envoyer(to, texte, SmsSender.SOURCE_RESULTAT, UUID.randomUUID());

        // L'avis est parti : la tâche de rappel n'a plus à s'en occuper.
        report.setPatientNotifiedAt(LocalDateTime.now());
        reportRepository.save(report);

        logAction(reportId, "SMS envoyé", userId, report.getBranchId());

        return new SmsResponseDto("sent");
    }

    /**
     * Prévient le patient que son résultat est disponible : SMS, puis appel.
     *
     * <p>Les deux partent, chacun pour soi : un SMS refusé ne doit pas priver le
     * patient de son appel, ni l'inverse. L'avis compte comme donné dès que l'un
     * des deux est parti ; si aucun ne part, l'erreur remonte et la tâche de
     * rappel réessaie au passage suivant.</p>
     *
     * <p>Un patient sans numéro n'est pas réessayé : une ligne {@code SANS_NUMERO}
     * le signale au secrétariat, et l'avis est marqué donné pour ne pas reboucler.</p>
     */
    @Override
    @Transactional
    public NotifyResponseDto notifyPatient(UUID reportId, UUID userId) {
        // Global : appelé aussi hors requête (rappel planifié), sans agence courante.
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte-rendu", reportId));

        if (numeroDuPatient(report) == null) {
            AppelByReport appel = nouvelAppel(report);
            appel.setIssue(IssueRappel.SANS_NUMERO);
            appel.setFinishedAt(LocalDateTime.now());
            appelByReportRepository.save(appel);
            report.setPatientNotifiedAt(LocalDateTime.now());
            reportRepository.save(report);
            logAction(reportId, "Rappel impossible : aucun numéro", userId, report.getBranchId());
            return new NotifyResponseDto("NONE", reportId, null,
                    "Aucun numéro de téléphone : le patient est à prévenir par le secrétariat");
        }

        RuntimeException echec = null;
        boolean smsParti = false;
        try {
            sendSms(reportId, userId);
            smsParti = true;
        } catch (RuntimeException e) {
            log.warn("Compte-rendu {} : SMS de résultat non envoyé : {}", reportId, e.getMessage());
            echec = e;
        }
        try {
            CallResponseDto call = callPatient(reportId, userId);
            return new NotifyResponseDto("CALL", reportId, call.appelId(),
                    smsParti ? "SMS envoyé et appel programmé" : "Appel programmé (SMS non envoyé)");
        } catch (RuntimeException e) {
            log.warn("Compte-rendu {} : appel de rappel non lancé : {}", reportId, e.getMessage());
            if (!smsParti) {
                throw echec != null ? echec : e;
            }
        }
        return new NotifyResponseDto("SMS", reportId, null, "SMS envoyé (appel non lancé)");
    }

    @Override
    // Pas en lecture seule : un appel en cours relu chez FluidChat est réécrit.
    @Transactional
    public AppelResponseDto getAppelStatus(UUID reportId) {
        AppelByReport appel = appelByReportRepository.findFirstByReportIdOrderByCreatedAtDesc(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Appel", reportId));
        // Suivi en direct depuis l'écran : un appel en cours est relu chez FluidChat
        // à chaque consultation, sans attendre la tâche de suivi.
        if (appel.getIssue() == IssueRappel.EN_COURS) {
            try {
                actualiser(appel);
            } catch (RuntimeException e) {
                log.warn("Appel {} non relu : {}", appel.getAppelId(), e.getMessage());
            }
        }
        return new AppelResponseDto(appel.getId(), reportId, appel.getAppelId(), appel.getCreatedAt(),
                appel.getIssue(), appel.getResult(), appel.getListenedPct(), appel.getAttempts());
    }

    private static AppelByReport nouvelAppel(Report report) {
        AppelByReport appel = new AppelByReport();
        appel.setBranchId(report.getBranchId());
        appel.setReport(report);
        return appel;
    }

    /** Numéro du patient au format international sans « + », ou nul s'il n'en a pas. */
    private static String numeroDuPatient(Report report) {
        var patient = report.getTestOrder().getPatient();
        String telephone = patient.getTelephone1();
        if (telephone == null || telephone.isBlank()) {
            telephone = patient.getTelephone2();
        }
        return PhoneNumbers.toInternational(telephone);
    }

    private void logAction(UUID reportId, String action, UUID userId, UUID branchId) {
        reportRepository.findById(reportId).ifPresent(report -> {
            LogReport log = new LogReport();
            log.setBranchId(branchId);
            log.setReport(report);
            log.setAction(action);
            log.setDescription("Action: " + action + " on report: " + reportId);
            // L'auteur peut manquer : la notification automatique déclenchée à la
            // validation part parfois sans contexte de sécurité. Journaliser sans
            // auteur vaut mieux que ne pas journaliser — et findById(null) lèverait.
            if (userId != null) {
                userRepository.findById(userId).ifPresent(log::setUser);
            }
            logReportRepository.save(log);
        });
    }
}

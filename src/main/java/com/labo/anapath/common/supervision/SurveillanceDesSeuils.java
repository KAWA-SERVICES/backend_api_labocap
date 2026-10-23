package com.labo.anapath.common.supervision;

import com.labo.anapath.common.email.EmailService;
import com.labo.anapath.common.email.NotificationSettings;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.labo.anapath.common.supervision.CompteurDAlertes.Type.ECHEC_DE_CONNEXION;
import static com.labo.anapath.common.supervision.CompteurDAlertes.Type.ERREUR_SERVEUR;
import static com.labo.anapath.common.supervision.CompteurDAlertes.Type.LIEN_DE_FACTURE_REFUSE;
import static com.labo.anapath.common.supervision.CompteurDAlertes.Type.VERROUILLAGE;

/**
 * Toutes les dix minutes, compare les compteurs d'alerte et l'espace disque à
 * leurs seuils, et prévient par courriel.
 *
 * <p>Deux cercles de destinataires : les administrateurs du laboratoire
 * (réglage {@code admin_mails}) reçoivent ce qui touche aux comptes — rafale de
 * connexions ratées, verrouillages — car c'est à eux de reconnaître un collègue
 * qui s'acharne ou un inconnu ; le prestataire ({@code ALERT_EMAIL}) reçoit
 * tout, dont les erreurs serveur et le disque, qui ne regardent que lui.</p>
 *
 * <p>Un même seuil n'écrit pas plus d'une fois par heure : une attaque qui dure
 * produirait sinon six courriels identiques par heure, et la boîte cesserait
 * d'être lue.</p>
 */
@Slf4j
@Component
public class SurveillanceDesSeuils {

    static final int SEUIL_ECHECS_DE_CONNEXION = 20;
    static final int SEUIL_VERROUILLAGES = 5;
    static final int SEUIL_ERREURS_SERVEUR = 10;
    /**
     * Un patient qui se trompe de lien, ou rouvre un lien expiré, fait quelques
     * refus ; cinquante en dix minutes, c'est quelqu'un qui essaie des jetons.
     */
    static final int SEUIL_LIENS_DE_FACTURE_REFUSES = 50;
    static final int SEUIL_DISQUE_LIBRE_POURCENT = 15;
    static final Duration SILENCE_ENTRE_DEUX_ENVOIS = Duration.ofHours(1);

    private final CompteurDAlertes compteur;
    private final EmailService emailService;
    private final SettingAppRepository settingAppRepository;
    private final String alertEmail;
    private final List<Path> volumesSurveilles;
    private final Map<String, Instant> dernierEnvoiParSeuil = new ConcurrentHashMap<>();

    public SurveillanceDesSeuils(CompteurDAlertes compteur,
                                 EmailService emailService,
                                 SettingAppRepository settingAppRepository,
                                 @Value("${app.supervision.alert-email:}") String alertEmail,
                                 @Value("${app.storage.path:/tmp/labo/storage}") String storagePath) {
        this.compteur = compteur;
        this.emailService = emailService;
        this.settingAppRepository = settingAppRepository;
        this.alertEmail = alertEmail == null ? "" : alertEmail.trim();
        this.volumesSurveilles = List.of(Path.of(storagePath), Path.of("/"));
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Africa/Porto-Novo")
    public void evaluer() {
        long echecs = compteur.maximumSur(ECHEC_DE_CONNEXION, Duration.ofMinutes(10));
        if (echecs > SEUIL_ECHECS_DE_CONNEXION) {
            alerter("connexions", administrateursEtPrestataire(),
                    "[Labo] " + echecs + " connexions ratées en 10 minutes",
                    echecs + " échecs de connexion ou de code en 10 minutes (seuil : "
                            + SEUIL_ECHECS_DE_CONNEXION + "). Possible tentative d'intrusion : "
                            + "consulter le journal des accès.");
        }
        long verrouillages = compteur.maximumSur(VERROUILLAGE, Duration.ofHours(1));
        if (verrouillages > SEUIL_VERROUILLAGES) {
            alerter("verrouillages", administrateursEtPrestataire(),
                    "[Labo] " + verrouillages + " comptes verrouillés en 1 heure",
                    verrouillages + " verrouillages de compte en une heure (seuil : "
                            + SEUIL_VERROUILLAGES + "). Vérifier s'il s'agit des mêmes utilisateurs.");
        }
        long erreurs = compteur.maximumSur(ERREUR_SERVEUR, Duration.ofMinutes(5));
        if (erreurs > SEUIL_ERREURS_SERVEUR) {
            alerter("erreurs-serveur", prestataire(),
                    "[Labo] " + erreurs + " erreurs serveur en 5 minutes",
                    erreurs + " réponses HTTP 5xx en 5 minutes (seuil : " + SEUIL_ERREURS_SERVEUR
                            + "). Consulter le journal applicatif.");
        }
        long liensRefuses = compteur.maximumSur(LIEN_DE_FACTURE_REFUSE, Duration.ofMinutes(10));
        if (liensRefuses > SEUIL_LIENS_DE_FACTURE_REFUSES) {
            alerter("liens-de-facture", prestataire(),
                    "[Labo] " + liensRefuses + " liens de facture refusés en 10 minutes",
                    liensRefuses + " ouvertures de lien de facture inconnu ou expiré en 10 minutes (seuil : "
                            + SEUIL_LIENS_DE_FACTURE_REFUSES + "). Possible essai de jetons : "
                            + "consulter les journaux nginx des adresses /f/.");
        }
        for (Path volume : volumesSurveilles) {
            verifierLeDisque(volume);
        }
        compteur.oublierLesVieillesMinutes();
    }

    private void verifierLeDisque(Path volume) {
        try {
            FileStore fs = Files.getFileStore(volume);
            long total = fs.getTotalSpace();
            if (total <= 0) return;
            long librePourcent = fs.getUsableSpace() * 100 / total;
            if (librePourcent < SEUIL_DISQUE_LIBRE_POURCENT) {
                alerter("disque:" + volume, prestataire(),
                        "[Labo] Disque presque plein : " + librePourcent + " % libres sur " + volume,
                        "Il reste " + librePourcent + " % (" + fs.getUsableSpace() / (1024 * 1024)
                                + " Mo) sur " + volume + ". Seuil : " + SEUIL_DISQUE_LIBRE_POURCENT + " %.");
            }
        } catch (IOException | RuntimeException e) {
            // Volume absent (chemin de stockage non monté, par exemple) : rien à
            // mesurer, et une tâche de surveillance ne doit pas mourir pour ça.
            log.warn("Espace disque de {} non mesurable : {}", volume, e.getMessage());
        }
    }

    private void alerter(String seuil, List<String> destinataires, String sujet, String corps) {
        Instant dernier = dernierEnvoiParSeuil.get(seuil);
        if (dernier != null && dernier.plus(SILENCE_ENTRE_DEUX_ENVOIS).isAfter(Instant.now())) {
            return;
        }
        if (destinataires.isEmpty()) {
            log.warn("Alerte « {} » sans destinataire (ALERT_EMAIL et admin_mails vides) : {}", sujet, corps);
            return;
        }
        log.warn("Alerte de supervision : {}", sujet);
        emailService.sendAlerteSupervision(destinataires, sujet, corps);
        dernierEnvoiParSeuil.put(seuil, Instant.now());
    }

    private List<String> prestataire() {
        return alertEmail.isEmpty() ? List.of() : List.of(alertEmail);
    }

    /**
     * Les administrateurs de toutes les agences : l'alerte n'est rattachée à
     * aucune branche, et {@code findByKey} refuserait deux agences ayant chacune
     * leur liste.
     */
    private List<String> administrateursEtPrestataire() {
        Set<String> adresses = new LinkedHashSet<>();
        for (SettingApp reglage : settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of("admin_mails"))) {
            adresses.addAll(NotificationSettings.parseEmails(reglage.getValue()));
        }
        adresses.addAll(prestataire());
        return new ArrayList<>(adresses);
    }
}

package com.labo.anapath.dashboard;

import com.labo.anapath.common.email.EmailService;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Autonomie du crédit SMS et appels, calculée par LabSys et non par le seuil
 * fixe de FluidChat : un labo qui envoie 200 SMS par jour et un autre qui en
 * envoie 5 n'ont pas le même « bas ».
 *
 * <p>Consommation par jour = SMS et appels débités sur les {@value #JOURS_OBSERVES}
 * derniers jours, divisés par {@value #JOURS_OBSERVES}. Crédit <b>bas</b> sous
 * {@value #JOURS_D_AUTONOMIE_MINIMUM} jours d'autonomie, <b>épuisé</b> sous le prix
 * d'une unité SMS. Sans consommation récente, ou en forfait illimité, jamais bas.</p>
 *
 * <p>Tant que le crédit est bas, un courriel toutes les {@value #HEURES_ENTRE_DEUX_RAPPELS}
 * heures au secrétariat et aux super-admins ; le passage à épuisé écrit tout de suite.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutonomieDuCredit {

    static final int JOURS_OBSERVES = 7;
    static final int JOURS_D_AUTONOMIE_MINIMUM = 3;
    static final int HEURES_ENTRE_DEUX_RAPPELS = 8;
    private static final Duration FRAICHEUR_DE_LA_CONSOMMATION = Duration.ofHours(1);

    public enum Niveau { OK, BAS, EPUISE }

    /** {@code autonomieJours} nul : pas de consommation récente, rien à projeter. */
    public record Etat(Niveau niveau, Long autonomieJours, long consommationJourFcfa, long seuilBasFcfa) {}

    private final FluidChatClient fluidChat;
    private final UserRepository userRepository;
    private final EmailService emailService;

    // ponytail: en mémoire, une seule instance d'API ; un redémarrage refait au plus un calcul et un courriel.
    private volatile long consommationJourFcfa;
    private volatile Instant consommationCalculeeLe;
    private volatile Niveau dernierNiveauAlerte = Niveau.OK;
    private volatile Instant dernierCourriel;

    public Etat etat(FluidChatClient.Portefeuille p) {
        return etat(p, Instant.now());
    }

    Etat etat(FluidChatClient.Portefeuille p, Instant maintenant) {
        if (!"unit".equals(p.plan()) || p.balanceFcfa() == null) {
            return new Etat(Niveau.OK, null, 0, 0);
        }
        long solde = p.balanceFcfa();
        long prixSms = p.prices() == null ? 0 : p.prices().smsPartFcfa();
        long parJour = consommationJour(maintenant);
        long seuil = parJour * JOURS_D_AUTONOMIE_MINIMUM;
        Niveau niveau = solde < Math.max(prixSms, 1) ? Niveau.EPUISE
                : solde < seuil ? Niveau.BAS : Niveau.OK;
        return new Etat(niveau, parJour == 0 ? null : solde / parJour, parJour, seuil);
    }

    private long consommationJour(Instant maintenant) {
        Instant calcule = consommationCalculeeLe;
        if (calcule == null || calcule.plus(FRAICHEUR_DE_LA_CONSOMMATION).isBefore(maintenant)) {
            try {
                consommationJourFcfa = mesurer(maintenant);
            } catch (RuntimeException e) {
                // Registre illisible : on garde la dernière mesure plutôt que de
                // priver la tuile et le bandeau du solde ; nouvel essai dans une heure.
                log.warn("Registre du crédit SMS et appels illisible : {}", e.getMessage());
            }
            consommationCalculeeLe = maintenant;
        }
        return consommationJourFcfa;
    }

    /** Le registre est mensuel : début de mois, on lit aussi le mois précédent. */
    private long mesurer(Instant maintenant) {
        Instant depuis = maintenant.minus(Duration.ofDays(JOURS_OBSERVES));
        YearMonth ce = YearMonth.from(maintenant.atZone(ZoneOffset.UTC));
        YearMonth debut = YearMonth.from(depuis.atZone(ZoneOffset.UTC));
        long debite = 0;
        for (YearMonth mois = debut; !mois.isAfter(ce); mois = mois.plusMonths(1)) {
            for (FluidChatClient.Mouvement m : fluidChat.mouvements(mois.toString())) {
                boolean usage = "sms".equals(m.kind()) || "call".equals(m.kind());
                if (usage && m.amountFcfa() < 0 && Instant.parse(m.createdAt()).isAfter(depuis)) {
                    debite -= m.amountFcfa();
                }
            }
        }
        return debite / JOURS_OBSERVES;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Africa/Porto-Novo")
    public void surveiller() {
        surveiller(Instant.now());
    }

    void surveiller(Instant maintenant) {
        Etat etat;
        try {
            etat = etat(fluidChat.portefeuille(), maintenant);
        } catch (RuntimeException e) {
            // FluidChat absent ou injoignable : rien à mesurer, on réessaie dans dix minutes.
            log.debug("Crédit SMS et appels non lu : {}", e.getMessage());
            return;
        }
        if (etat.niveau() == Niveau.OK) {
            dernierNiveauAlerte = Niveau.OK;
            return;
        }
        boolean aggrave = etat.niveau() != dernierNiveauAlerte;
        Instant dernier = dernierCourriel;
        boolean rappelDu = dernier == null
                || !dernier.plus(Duration.ofHours(HEURES_ENTRE_DEUX_RAPPELS)).isAfter(maintenant);
        if (!aggrave && !rappelDu) {
            return;
        }
        List<String> destinataires = destinataires();
        if (destinataires.isEmpty()) {
            log.warn("Crédit SMS et appels {} : aucun secrétariat ni super-admin avec une adresse.", etat.niveau());
            return;
        }
        emailService.sendAlerteSupervision(destinataires, sujet(etat), corps(etat));
        dernierNiveauAlerte = etat.niveau();
        dernierCourriel = maintenant;
    }

    private List<String> destinataires() {
        Set<String> adresses = new LinkedHashSet<>();
        for (User u : userRepository.findAlertesCredit()) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) adresses.add(u.getEmail().trim());
        }
        return List.copyOf(adresses);
    }

    static String sujet(Etat e) {
        return e.niveau() == Niveau.EPUISE
                ? "[Labo] Crédit SMS et appels épuisé"
                : "[Labo] Crédit SMS et appels bas : environ " + e.autonomieJours() + " jour(s) d'autonomie";
    }

    static String corps(Etat e) {
        String debut = e.niveau() == Niveau.EPUISE
                ? "Le crédit SMS et appels est épuisé : plus aucun SMS ni appel automatique ne part."
                : "Le crédit SMS et appels ne couvre plus qu'environ " + e.autonomieJours()
                        + " jour(s) de consommation (minimum souhaité : " + JOURS_D_AUTONOMIE_MINIMUM + " jours).";
        return debut + "\n\nConsommation moyenne : " + e.consommationJourFcfa() + " F par jour sur les "
                + JOURS_OBSERVES + " derniers jours.\nRechargez-le depuis le tableau de bord de LabSys.";
    }
}

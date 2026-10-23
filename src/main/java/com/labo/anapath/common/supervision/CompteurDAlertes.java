package com.labo.anapath.common.supervision;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Compteurs en mémoire des événements qui, en rafale, signalent une attaque ou
 * une panne : connexions ratées, comptes verrouillés, réponses 5xx.
 *
 * <p>Les événements sont comptés par minute, pas conservés un à un : sous une
 * rafale de milliers d'erreurs, une liste d'horodatages grossirait sans limite,
 * alors qu'une heure tient en soixante cases. La minute suffit comme grain, les
 * seuils étant « plus de N en 5, 10 ou 60 minutes ».</p>
 *
 * <p>Redémarrer l'API remet les compteurs à zéro : une rafale qui chevauche un
 * redéploiement peut passer sous le seuil. Acceptable pour une alerte, qui ne
 * remplace pas le journal.</p>
 */
@Component
public class CompteurDAlertes {

    public enum Type { ECHEC_DE_CONNEXION, VERROUILLAGE, ERREUR_SERVEUR, LIEN_DE_FACTURE_REFUSE }

    /** Au-delà, les minutes sont oubliées : la plus longue fenêtre de seuil est d'une heure. */
    static final long MINUTES_CONSERVEES = 60;

    private final Map<Type, ConcurrentHashMap<Long, LongAdder>> parMinute = new EnumMap<>(Type.class);

    public CompteurDAlertes() {
        for (Type type : Type.values()) {
            parMinute.put(type, new ConcurrentHashMap<>());
        }
    }

    /** Mot de passe refusé, compte désactivé, ou code à usage unique faux. */
    public void echecDeConnexion() {
        compter(Type.ECHEC_DE_CONNEXION, minuteCourante());
    }

    /** Compte verrouillé après trop de codes faux. */
    public void verrouillage() {
        compter(Type.VERROUILLAGE, minuteCourante());
    }

    /** Lien public de facture inconnu ou expiré : en rafale, quelqu'un essaie des jetons. */
    public void lienDeFactureRefuse() {
        compter(Type.LIEN_DE_FACTURE_REFUSE, minuteCourante());
    }

    /** Réponse HTTP de statut 500 ou plus. */
    public void erreurServeur() {
        compter(Type.ERREUR_SERVEUR, minuteCourante());
    }

    void compter(Type type, long minute) {
        parMinute.get(type).computeIfAbsent(minute, m -> new LongAdder()).increment();
    }

    /**
     * Plus grand nombre d'événements sur une fenêtre glissante de la durée
     * donnée, parmi les minutes encore conservées.
     *
     * <p>La tâche d'évaluation passe toutes les dix minutes, mais un seuil
     * peut porter sur cinq : compter seulement « les cinq dernières minutes »
     * manquerait une rafale survenue au début de l'intervalle. On regarde
     * donc toutes les positions possibles de la fenêtre.</p>
     */
    public long maximumSur(Type type, Duration fenetre) {
        long largeur = Math.max(1, fenetre.toMinutes());
        Map<Long, LongAdder> compteurs = parMinute.get(type);
        if (compteurs.isEmpty()) {
            return 0;
        }
        long premiere = compteurs.keySet().stream().mapToLong(Long::longValue).min().orElseThrow();
        long derniere = compteurs.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
        long maximum = 0;
        for (long debut = premiere; debut <= derniere; debut++) {
            long total = 0;
            for (long m = debut; m < debut + largeur; m++) {
                LongAdder c = compteurs.get(m);
                if (c != null) total += c.sum();
            }
            maximum = Math.max(maximum, total);
        }
        return maximum;
    }

    /** Oublie les minutes plus vieilles qu'une heure, pour que les cases ne s'accumulent pas. */
    public void oublierLesVieillesMinutes() {
        long limite = minuteCourante() - MINUTES_CONSERVEES;
        parMinute.values().forEach(c -> c.keySet().removeIf(m -> m < limite));
    }

    private static long minuteCourante() {
        return Instant.now().getEpochSecond() / 60;
    }
}

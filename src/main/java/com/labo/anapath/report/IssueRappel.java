package com.labo.anapath.report;

import java.util.Set;

/**
 * Ce que le rappel automatique a donné, du point de vue du secrétariat.
 *
 * <p>Calculé une fois, à partir du statut et de l'issue renvoyés par FluidChat,
 * et stocké : la liste du suivi, les compteurs du tableau de bord et le bandeau
 * lisent la même colonne.</p>
 */
public enum IssueRappel {
    EN_COURS,
    /** Audio écouté en entier, ou au moins à {@value #SEUIL_ECOUTE_PCT} %. */
    JOINT,
    /** Décroché, mais raccroché avant d'avoir entendu l'essentiel. */
    PARTIEL,
    SANS_REPONSE,
    INJOIGNABLE,
    /** Aucun numéro sur la fiche patient : aucun appel n'est parti. */
    SANS_NUMERO,
    ANNULE;

    /** Part d'audio écoutée à partir de laquelle le message est réputé passé. */
    public static final int SEUIL_ECOUTE_PCT = 60;

    /** Issues qui laissent un patient non prévenu : le secrétariat doit le rappeler. */
    public static final Set<IssueRappel> A_RAPPELER = Set.of(PARTIEL, SANS_REPONSE, INJOIGNABLE, SANS_NUMERO);

    /** Issue d'un appel d'après son statut et son résultat FluidChat (§4 et §5 de son API). */
    public static IssueRappel de(String status, String result, Integer listenedPct) {
        if (status == null || "queued".equals(status) || "calling".equals(status)) {
            return EN_COURS;
        }
        if ("canceled".equals(status)) {
            return ANNULE;
        }
        if ("completed".equals(result)) {
            return JOINT;
        }
        if ("partial".equals(result)) {
            return listenedPct != null && listenedPct >= SEUIL_ECOUTE_PCT ? JOINT : PARTIEL;
        }
        if ("no_answer".equals(result) || "busy".equals(result)) {
            return SANS_REPONSE;
        }
        return INJOIGNABLE;
    }
}

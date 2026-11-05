package com.labo.anapath.common.notification;

/**
 * Mise au format international des numéros de téléphone avant envoi à OurVoice.
 *
 * <p><b>RÈGLE R7.</b> Les numéros sont saisis au format local béninois
 * ({@code 97000001}) alors qu'OurVoice attend l'indicatif pays collé au numéro
 * ({@code 2290197000001}, « 01 » compris depuis la réforme du 30/11/2024).
 * La règle vivait en dur, dupliquée, dans le service de notification des comptes-rendus ; la notification des factures en a le même
 * besoin, d'où cette extraction.</p>
 *
 * <p>La normalisation va au-delà du simple préfixe : les numéros réels portent
 * des espaces, des points ou un {@code +} de tête, et un numéro déjà international
 * ne doit pas se voir préfixer une seconde fois — {@code 2292290197000001} partirait
 * chez OurVoice sans qu'aucune erreur ne remonte, le SMS étant simplement perdu.</p>
 */
public final class PhoneNumbers {

    /** Indicatif du Bénin, sans {@code +}, tel qu'attendu par OurVoice. */
    public static final String INDICATIF_BENIN = "229";

    /** Longueur d'un ancien numéro béninois (avant le 30/11/2024), sans indicatif. */
    private static final int LONGUEUR_NUMERO_LOCAL = 8;

    private PhoneNumbers() {
    }

    /**
     * Met un numéro au format attendu par OurVoice.
     *
     * @param numero numéro tel que saisi ; peut contenir espaces, points, tirets,
     *               parenthèses ou un {@code +} de tête
     * @return le numéro en chiffres, préfixé de l'indicatif béninois, ou
     *         {@code null} si l'entrée est vide ou ne contient aucun chiffre
     */
    public static String toInternational(String numero) {
        String international = avecIndicatif(numero);
        // Depuis le 30 novembre 2024, un numéro béninois fait 10 chiffres : « 01 »
        // suivi de l'ancien numéro. 11 chiffres = 229 + un ancien numéro à 8 chiffres,
        // qui n'aboutit plus : sans le « 01 », la passerelle en fait un numéro à 14
        // chiffres et le SMS ou l'appel, accepté, n'arrive jamais.
        return international != null && international.length() == INDICATIF_BENIN.length() + LONGUEUR_NUMERO_LOCAL
                ? INDICATIF_BENIN + "01" + international.substring(INDICATIF_BENIN.length())
                : international;
    }

    /**
     * Second numéro à essayer ({@code fallback_to} de FluidChat) : le téléphone 2,
     * quand le téléphone 1 est aussi renseigné et que ce n'est pas le même numéro.
     *
     * @return au format de {@link #toInternational}, ou {@code null}
     */
    public static String secondaire(String telephone1, String telephone2) {
        String premier = toInternational(telephone1);
        String second = toInternational(telephone2);
        return premier != null && second != null && !premier.equals(second) ? second : null;
    }

    private static String avecIndicatif(String numero) {
        if (numero == null) {
            return null;
        }
        String chiffres = numero.replaceAll("[^0-9]", "");
        if (chiffres.isEmpty()) {
            return null;
        }
        // 00229… est la forme internationale composée depuis un poste fixe.
        //
        // Le « 00 » ne se retire que s'il précède l'indicatif : sans cette
        // condition, tout numéro commençant par deux zéros perdait ses deux
        // premiers chiffres et repartait amputé — dix patients de la base sont
        // dans ce cas, et aucun n'écrit son numéro sous la forme 00229.
        if (chiffres.startsWith("00" + INDICATIF_BENIN)) {
            chiffres = chiffres.substring(2);
        }
        // Un numéro béninois local fait 8 chiffres, et un fixe peut commencer par
        // « 229 » (par exemple 229 12 34 5). Le seul « 229 » de tête ne suffit donc
        // pas à conclure : on n'y voit l'indicatif que si le numéro est plus long
        // qu'un numéro local.
        if (chiffres.length() > LONGUEUR_NUMERO_LOCAL && chiffres.startsWith(INDICATIF_BENIN)) {
            return chiffres;
        }
        return INDICATIF_BENIN + chiffres;
    }
}

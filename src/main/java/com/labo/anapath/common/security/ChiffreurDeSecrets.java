package com.labo.anapath.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Chiffre les secrets courts qu'il faut pouvoir remontrer.
 *
 * <h2>Pourquoi il existe</h2>
 *
 * <p>Un secret qu'on vérifie se hache — c'est le cas du PIN, et il le reste.
 * Mais un secret qu'il faut <b>réafficher</b> ne peut pas être haché : le code
 * d'enrôlement doit pouvoir être remontré à l'administrateur qui rouvre
 * l'écran, sinon le QR n'existe qu'un instant et refermer une fenêtre coûte un
 * accès.</p>
 *
 * <p>Le stocker en clair mettrait tous les codes du laboratoire dans la
 * moindre sauvegarde. Chiffré, une copie de la base ne suffit plus : il faut
 * aussi la configuration du serveur.</p>
 *
 * <h2>Ce qu'il ne protège pas</h2>
 *
 * <p>Rien contre qui tient à la fois la base et la clé. C'est une séparation,
 * pas un coffre — elle vaut contre l'export, la sauvegarde égarée, l'accès
 * lecture d'un prestataire, et rien d'autre.</p>
 *
 * <h2>Sans clé</h2>
 *
 * <p>Le service reste présent mais inactif : rien n'est chiffré, donc rien
 * n'est conservé de relisible, et l'écran d'administration se contente de dire
 * que le code n'est pas réaffichable. Un démarrage refusé faute de clé
 * arrêterait des installations qui n'ont jamais demandé cette fonction.</p>
 */
@Slf4j
@Component
public class ChiffreurDeSecrets {

    /** AES-GCM : chiffre et scelle d'un même geste, comme le chiffreur de fichiers. */
    private static final String ALGORITHME = "AES/GCM/NoPadding";
    private static final int TAILLE_IV = 12;
    private static final int TAILLE_SCEAU_BITS = 128;
    private static final int TAILLE_CLE = 32;

    /**
     * Marque de format. Elle rend un jour possible de changer d'algorithme sans
     * confondre les anciennes valeurs avec les nouvelles.
     */
    private static final String MARQUE = "v1:";

    private final SecretKey cle;
    private final SecureRandom alea = new SecureRandom();

    /**
     * @param cleSecrets clé dédiée, 32 octets en base64
     * @param cleFichiers clé du chiffrement des fichiers, employée en second
     *                    recours pour ne pas imposer une seconde clé à qui en a
     *                    déjà posé une
     */
    public ChiffreurDeSecrets(
            @Value("${app.secrets.key:}") String cleSecrets,
            @Value("${app.storage.encryption.key:}") String cleFichiers) {
        String brute = (cleSecrets == null || cleSecrets.isBlank()) ? cleFichiers : cleSecrets;
        SecretKey lue = null;
        if (brute != null && !brute.isBlank()) {
            try {
                byte[] octets = Base64.getDecoder().decode(brute.trim());
                if (octets.length == TAILLE_CLE) {
                    lue = new SecretKeySpec(octets, "AES");
                } else {
                    log.warn("Clé de chiffrement des secrets ignorée : {} octets au lieu de {}.",
                            octets.length, TAILLE_CLE);
                }
                Arrays.fill(octets, (byte) 0);
            } catch (IllegalArgumentException e) {
                log.warn("Clé de chiffrement des secrets ignorée : base64 invalide.");
            }
        }
        this.cle = lue;
        if (lue == null) {
            log.info("Chiffrement des secrets courts inactif : "
                    + "les codes d'enrôlement ne seront pas réaffichables.");
        }
    }

    /** La valeur porte-t-elle la marque de format, donc est-elle chiffrée ? */
    public static boolean estChiffre(String valeur) {
        return valeur != null && valeur.startsWith(MARQUE);
    }

    /** Une clé est-elle configurée ? */
    public boolean estActif() {
        return cle != null;
    }

    /**
     * Une sous-clé propre à un usage, dérivée de la clé des secrets (HMAC-SHA256
     * de l'usage) : ce qu'on empreinte avec ne peut pas se retrouver par
     * dictionnaire sans la clé, et deux usages n'empreintent pas pareil.
     *
     * @return 32 octets, ou {@code null} si aucune clé n'est configurée
     */
    public byte[] deriver(String usage) {
        if (cle == null) return null;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cle.getEncoded(), "HmacSHA256"));
            return mac.doFinal(usage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Chiffre un secret, ou rend nul si aucune clé n'est configurée.
     *
     * <p>Rendre nul plutôt que lever : l'appelant enregistre alors une ligne
     * sans valeur réaffichable, ce qui est exactement le comportement d'avant.
     * Une exception ferait échouer la création d'un accès pour une commodité
     * d'affichage.</p>
     */
    public String chiffrer(String clair) {
        if (cle == null || clair == null) return null;
        try {
            byte[] iv = new byte[TAILLE_IV];
            alea.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHME);
            cipher.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(TAILLE_SCEAU_BITS, iv));
            byte[] scelle = cipher.doFinal(clair.getBytes(StandardCharsets.UTF_8));

            byte[] tout = new byte[iv.length + scelle.length];
            System.arraycopy(iv, 0, tout, 0, iv.length);
            System.arraycopy(scelle, 0, tout, iv.length, scelle.length);
            return MARQUE + Base64.getEncoder().encodeToString(tout);
        } catch (Exception e) {
            log.error("Chiffrement d'un secret impossible", e);
            return null;
        }
    }

    /**
     * Déchiffre, ou rend nul si la valeur est absente, d'un autre format, ou
     * scellée avec une autre clé.
     *
     * <p>Nul plutôt qu'une exception, là encore : une clé changée doit se
     * traduire par « code non réaffichable », pas par un écran en erreur.</p>
     */
    public String dechiffrer(String chiffre) {
        if (cle == null || chiffre == null || !chiffre.startsWith(MARQUE)) return null;
        try {
            byte[] tout = Base64.getDecoder().decode(chiffre.substring(MARQUE.length()));
            if (tout.length <= TAILLE_IV) return null;
            byte[] iv = Arrays.copyOfRange(tout, 0, TAILLE_IV);
            byte[] scelle = Arrays.copyOfRange(tout, TAILLE_IV, tout.length);
            Cipher cipher = Cipher.getInstance(ALGORITHME);
            cipher.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(TAILLE_SCEAU_BITS, iv));
            return new String(cipher.doFinal(scelle), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Déchiffrement d'un secret impossible — clé changée ?");
            return null;
        }
    }
}

package com.labo.anapath.assistant.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Découpe un flux Server-Sent Events en couples (événement, données).
 *
 * <p>Le strict nécessaire de la norme : lignes {@code event:} et {@code data:}
 * (plusieurs {@code data:} se joignent par un saut de ligne), commentaires
 * {@code :} ignorés, un événement se termine sur une ligne vide.</p>
 */
final class LecteurSse {

    private LecteurSse() {}

    static void lire(InputStream flux, BiConsumer<String, String> surEvenement) throws IOException {
        try (var lecteur = new BufferedReader(new InputStreamReader(flux, StandardCharsets.UTF_8))) {
            String evenement = null;
            StringBuilder donnees = null;
            String ligne;
            while ((ligne = lecteur.readLine()) != null) {
                if (ligne.isEmpty()) {
                    if (donnees != null) surEvenement.accept(evenement, donnees.toString());
                    evenement = null;
                    donnees = null;
                } else if (ligne.startsWith(":")) {
                    // commentaire — les « ping » de maintien de connexion
                } else if (ligne.startsWith("event:")) {
                    evenement = ligne.substring(6).strip();
                } else if (ligne.startsWith("data:")) {
                    String valeur = ligne.substring(5);
                    if (valeur.startsWith(" ")) valeur = valeur.substring(1);
                    donnees = donnees == null ? new StringBuilder(valeur) : donnees.append('\n').append(valeur);
                }
            }
            if (donnees != null) surEvenement.accept(evenement, donnees.toString());
        }
    }
}

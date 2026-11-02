package com.labo.anapath.common.assistant;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Une action qu'un domaine met à la disposition de l'assistant IA.
 *
 * <p>Déclarée par le domaine lui-même ({@code @Bean} dans son
 * {@code OutilsAssistant…}), collectée par le moteur : celui-ci ne connaît
 * aucun service métier, et un domaine ajoute un outil sans toucher au moteur.</p>
 *
 * <p>Le moteur n'exécute jamais l'outil directement : il passe par un point de
 * passage unique qui vérifie la {@link #permission()} — la même que celle de
 * la route HTTP équivalente — puis valide les arguments contre {@link #typeArgs()}
 * (Bean Validation). Un outil prend l'agence et l'utilisateur dans le
 * {@link Contexte}, jamais dans les arguments que fournit le modèle : sans quoi
 * le modèle choisirait l'agence qu'il interroge.</p>
 *
 * @param nom          nom qualifié, {@code domaine_action} — ce que le modèle appelle (lettres, chiffres, « _ » : les deux fournisseurs refusent le point)
 * @param libelle      ce que l'écran affiche pendant l'appel (« Demandes d'examen consultées »)
 * @param description  ce que le modèle lit pour décider de l'appeler
 * @param schemaJson   schéma JSON des arguments (objet), écrit à la main
 * @param permission   la permission de la route HTTP équivalente
 * @param categorie    {@link Categorie#LECTURE} s'exécute, {@link Categorie#ECRITURE} attend une confirmation
 * @param texteLibre   l'outil renvoie de la prose qui peut porter des données de
 *                     santé non masquables : écarté tant que le masquage est actif
 * @param typeArgs     le record dans lequel les arguments sont lus et validés
 * @param executeur    l'action
 * @param resumeur     pour une écriture : ce que la carte de confirmation affiche, calculé par le serveur
 */
public record OutilAssistant(
        String nom,
        String libelle,
        String description,
        String schemaJson,
        String permission,
        Categorie categorie,
        boolean texteLibre,
        Class<?> typeArgs,
        Executeur<?> executeur,
        Resumeur<?> resumeur) {

    public enum Categorie { LECTURE, ECRITURE }

    /** Ce dont un outil dispose pour agir à la place de l'utilisateur. */
    public record Contexte(UUID userId, UUID branchId, Set<String> permissions) {}

    @FunctionalInterface
    public interface Executeur<A> {
        Resultat executer(Contexte ctx, A args);
    }

    @FunctionalInterface
    public interface Resumeur<A> {
        Confirmation resumer(Contexte ctx, A args);
    }

    /**
     * Ce que renvoie un outil.
     *
     * @param donnees  ce que le modèle lit et que la carte de résultat affiche
     * @param lectures les dossiers lus, pour le journal des accès — l'intercepteur
     *                 HTTP ne voit pas ces lectures, faites hors de toute route GET
     * @param ui       consigne d'interface (ex. {@code naviguer}) ; jamais lue par le modèle
     */
    public record Resultat(Object donnees, List<Lecture> lectures, Map<String, String> ui) {
        public static Resultat de(Object donnees, List<Lecture> lectures) {
            return new Resultat(donnees, lectures, Map.of());
        }
    }

    /** Un dossier lu : {@code entite} reprend les valeurs du journal des accès. */
    public record Lecture(String entite, UUID id) {
        public static Lecture patient(UUID id) { return new Lecture("PATIENT", id); }
        public static Lecture demande(UUID id) { return new Lecture("TEST_ORDER", id); }
        public static Lecture compteRendu(UUID id) { return new Lecture("REPORT", id); }
        public static Lecture facture(UUID id) { return new Lecture("INVOICE", id); }
    }

    /** La carte « À confirmer » : un titre et des lignes libellé / valeur. */
    public record Confirmation(String resume, List<Ligne> details) {
        public record Ligne(String libelle, String valeur) {}
    }

    /** Raccourci pour un outil de lecture. */
    public static <A> OutilAssistant lecture(String nom, String libelle, String description, String schemaJson,
                                             String permission, Class<A> typeArgs, Executeur<A> executeur) {
        return new OutilAssistant(nom, libelle, description, schemaJson, permission, Categorie.LECTURE,
                false, typeArgs, executeur, null);
    }

    /** Raccourci pour un outil d'écriture, toujours soumis à confirmation. */
    public static <A> OutilAssistant ecriture(String nom, String libelle, String description, String schemaJson,
                                              String permission, Class<A> typeArgs,
                                              Executeur<A> executeur, Resumeur<A> resumeur) {
        return new OutilAssistant(nom, libelle, description, schemaJson, permission, Categorie.ECRITURE,
                false, typeArgs, executeur, resumeur);
    }
}

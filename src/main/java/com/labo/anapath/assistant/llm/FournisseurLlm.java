package com.labo.anapath.assistant.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Un modèle de langage qui répond en flux.
 *
 * <p>Le moteur ne parle qu'en blocs neutres — la forme des blocs de l'API
 * Messages d'Anthropic, qui sert de lingua franca :
 * {@code {type:text,text}}, {@code {type:tool_use,id,name,input}},
 * {@code {type:tool_result,tool_use_id,content,is_error}}. Les rôles sont
 * {@code user}, {@code assistant} et {@code tool}. Chaque implémentation
 * traduit vers son format et retour ; un bloc qu'elle ne reconnaît pas
 * (la réflexion d'Anthropic, par exemple) est rendu tel quel pour être
 * renvoyé tel quel au tour suivant, et ignoré par l'autre fournisseur.</p>
 *
 * <p>L'appel est bloquant : il pousse ses événements dans {@code sortie} au fil
 * de l'eau et rend la main à la fin du flux. Le moteur l'appelle sur le thread
 * de la requête HTTP — voir {@code AssistantService}.</p>
 */
public interface FournisseurLlm {

    /** {@code ANTHROPIC} ou {@code OPENAI}, tel que stocké dans les réglages. */
    String code();

    void completer(Requete requete, Consumer<Evenement> sortie);

    /**
     * @param cleApi    la clé du laboratoire, déchiffrée
     * @param modele    l'identifiant de modèle choisi dans les réglages
     * @param systeme   la consigne système
     * @param messages  l'historique, chaque élément {@code {role, content:[blocs]}}
     * @param outils    {@code [{name, description, input_schema}]}
     */
    record Requete(String cleApi, String modele, String systeme, List<JsonNode> messages,
                   ArrayNode outils, int maxJetons) {
        /** Ni la clé ni l'historique : un log de la requête ne doit rien exposer. */
        @Override
        public String toString() {
            return "Requete[modele=" + modele + ", messages=" + messages.size() + "]";
        }
    }

    sealed interface Evenement permits TexteDelta, BlocTermine, Consommation, Fin {}

    /** Un morceau du texte de la réponse, à afficher au fur et à mesure. */
    record TexteDelta(String texte) implements Evenement {}

    /** Un bloc complet de la réponse, dans la forme neutre, à conserver. */
    record BlocTermine(JsonNode bloc) implements Evenement {}

    record Consommation(int entree, int sortie) implements Evenement {}

    /**
     * Fin du flux. {@code raison} : {@code end_turn}, {@code tool_use},
     * {@code max_tokens}, {@code refusal}… — normalisée sur les valeurs d'Anthropic.
     */
    record Fin(String raison) implements Evenement {}

    /** Le fournisseur a refusé l'appel ou s'est interrompu. */
    class Indisponible extends RuntimeException {
        public Indisponible(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

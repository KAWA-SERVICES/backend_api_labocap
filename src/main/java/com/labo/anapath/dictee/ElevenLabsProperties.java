package com.labo.anapath.dictee;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Transcription vocale par ElevenLabs (Speech to Text), préfixe {@code app.elevenlabs}.
 *
 * <p>La clé vit dans l'environnement : elle déclenche une facturation à la
 * seconde d'audio.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.elevenlabs")
@Getter
@Setter
public class ElevenLabsProperties {

    /** Racine de l'API ; résidence UE : {@code https://api.eu.residency.elevenlabs.io}. */
    private String baseUrl = "https://api.elevenlabs.io";

    private String apiKey;

    /** {@code scribe_v2_medical} : réentraîné sur l'audio clinique. */
    private String modelId = "scribe_v2_medical";

    /** ISO-639-3 ; fixer la langue évite une détection ratée sur une phrase courte. */
    private String languageCode = "fra";

    /**
     * Vrai : {@code enable_logging=false}, ElevenLabs n'enregistre ni l'audio ni
     * le texte. Réservé à l'offre Entreprise : sans elle, les requêtes échouent —
     * on préfère ce refus à des données de santé conservées chez un tiers.
     */
    private boolean zeroRetention = true;

    /** Vocabulaire anapath (termes, marqueurs) envoyé à chaque requête. */
    private List<String> motsCles = new ArrayList<>();

    /** Vrai si la clé est renseignée. */
    public boolean isUsable() {
        return StringUtils.hasText(apiKey);
    }
}

package com.labo.anapath.common.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Accès à l'API d'appels automatiques de FluidChat ({@code docs/API.md} de
 * fluidchat-saas), lu sous le préfixe {@code app.fluidchat}.
 *
 * <p>La clé vit dans l'environnement (secret GitHub Actions en production), pas
 * dans {@code setting_apps} : cette table est lue par l'écran Paramètres, et la
 * clé autorise des appels facturés sur la carte SIM du laboratoire.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.fluidchat")
@Getter
@Setter
public class FluidChatProperties {

    /** Racine de l'espace FluidChat, ex. {@code https://caap.chat-app.fluidpay.link}. */
    private String baseUrl;

    /** Clé d'application ({@code fc_live_…}), créée dans Réglages › API de FluidChat. */
    private String apiKey;

    /** Vrai si l'adresse et la clé sont renseignées. */
    public boolean isUsable() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    /** URL d'un chemin de l'API, barre oblique finale de {@link #baseUrl} tolérée. */
    String url(String chemin) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + chemin;
    }
}

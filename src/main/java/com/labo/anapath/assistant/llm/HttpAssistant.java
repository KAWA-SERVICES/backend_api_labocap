package com.labo.anapath.assistant.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Ce que les deux clients partagent : le client HTTP et la lecture d'une erreur. */
@Slf4j
final class HttpAssistant {

    private HttpAssistant() {}

    /**
     * Un client dédié : le {@code RestTemplate} partagé coupe à 10 s, et une
     * réponse de modèle met plus longtemps. 90 s restent sous le
     * {@code proxy_read_timeout} de nginx (120 s) : c'est l'API qui coupe, et
     * proprement, plutôt que le proxy au milieu du flux.
     */
    static RestClient client(RestClient.Builder builder, String url) {
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var fabrique = new JdkClientHttpRequestFactory(jdk);
        fabrique.setReadTimeout(Duration.ofSeconds(90));
        return builder.clone().baseUrl(url).requestFactory(fabrique).build();
    }

    /**
     * L'erreur d'un fournisseur, sans jamais reprendre la requête : elle porte la
     * clé dans ses en-têtes. Le corps de la réponse, lui, ne la contient pas.
     */
    static FournisseurLlm.Indisponible erreur(String fournisseur, int statut, InputStream corps) {
        String detail;
        try {
            detail = new String(corps.readNBytes(2000), StandardCharsets.UTF_8);
        } catch (IOException e) {
            detail = "";
        }
        String message = switch (statut) {
            case 401, 403 -> fournisseur + " refuse la clé API configurée.";
            case 429 -> fournisseur + " limite les appels pour le moment.";
            default -> fournisseur + " a répondu " + statut + ".";
        };
        // Au journal, le type d'erreur seulement : le message d'un fournisseur peut
        // citer un morceau de la requête, donc des données de patient.
        java.util.regex.Matcher type = java.util.regex.Pattern.compile("\"(?:type|code)\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,60})\"")
                .matcher(detail);
        log.warn("{} — réponse {} ({})", fournisseur, statut, type.find() ? type.group(1) : "sans type");
        return new FournisseurLlm.Indisponible(message, null);
    }

    /**
     * L'erreur survenue en plein flux : un message fixe pour l'écran, le type seul
     * au journal — le message du fournisseur peut citer la requête.
     */
    static String messageDeFlux(String fournisseur, String type) {
        String sur = type == null ? "" : type.replaceAll("[^A-Za-z0-9_.-]", "");
        log.warn("{} — erreur en cours de flux ({})", fournisseur, sur.isEmpty() ? "sans type" : sur);
        return switch (sur) {
            case "overloaded_error", "rate_limit_error", "rate_limit_exceeded" ->
                    fournisseur + " est très sollicité. Réessayez dans un instant.";
            default -> "La réponse de " + fournisseur + " a été interrompue. Réessayez.";
        };
    }
}

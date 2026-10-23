package com.labo.anapath.common.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filtre de limitation de débit (rate limiting) sur les routes ouvertes sans jeton.
 * <p>
 * Chaque route qui s'atteint sans être authentifié récompense qui la martèle :
 * la connexion rend un mot de passe, le challenge un code à six chiffres, la
 * réinitialisation un jeton, le lien public une facture. Elles sont donc
 * plafonnées par adresse : 5 essais par minute et 20 par heure pour les
 * routes d'authentification, 30 par minute pour les factures, un client
 * légitime n'ouvrant son lien qu'une poignée de fois.
 * </p>
 * <p>
 * L'adresse est {@code getRemoteAddr()} tel que Tomcat l'a rétablie derrière
 * nginx ({@code server.forward-headers-strategy: native}) : {@code X-Forwarded-For}
 * n'est cru que s'il vient d'un proxy interne. Lu ici directement, n'importe
 * quel client pouvait l'écrire et changer d'adresse à chaque essai.
 * </p>
 * <p>
 * Répond HTTP 429 en cas de dépassement.
 * </p>
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** Routes d'authentification, en POST, limitées par adresse. */
    static final Set<String> CHEMINS_AUTH = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/2fa/challenge",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/resend-2fa",
            "/api/v1/mobile/login");

    /** Préfixe des liens publics de téléchargement de facture. */
    private static final String CHEMIN_FACTURE_PUBLIQUE = "/api/v1/public/invoices/";

    /** Adresse courte du même lien, celle des SMS. */
    private static final String CHEMIN_FACTURE_COURT = "/f/";

    // ponytail: en mémoire, par instance ; un cache partagé si l'API tourne un jour en plusieurs copies.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> bucketsFacture = new ConcurrentHashMap<>();

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1))))
                .addLimit(Bandwidth.classic(20, Refill.intervally(20, Duration.ofHours(1))))
                .build();
    }

    private Bucket newBucketFacture() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(30, Refill.intervally(30, Duration.ofMinutes(1))))
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String chemin = request.getRequestURI();
        if (CHEMINS_AUTH.contains(chemin) && "POST".equals(request.getMethod())) {
            String ip = request.getRemoteAddr();
            // Un seau par route et par adresse : une connexion normale enchaîne
            // login, challenge, parfois un renvoi de code, sans se bloquer elle-même.
            Bucket bucket = buckets.computeIfAbsent(chemin + "|" + ip, k -> newBucket());
            if (!bucket.tryConsume(1)) {
                log.warn("Limite d'essais dépassée sur {} depuis {}", chemin, ip);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\":false,\"message\":\"Trop de tentatives. Réessayez dans quelques minutes.\"}");
                return;
            }
        } else if ((chemin.startsWith(CHEMIN_FACTURE_PUBLIQUE) || chemin.startsWith(CHEMIN_FACTURE_COURT))
                && "GET".equals(request.getMethod())) {
            String ip = request.getRemoteAddr();
            Bucket bucket = bucketsFacture.computeIfAbsent(ip, k -> newBucketFacture());
            if (!bucket.tryConsume(1)) {
                log.warn("Limite d'essais dépassée sur {} depuis {}", CHEMIN_FACTURE_PUBLIQUE, ip);
                // Le lien s'ouvre dans un navigateur : un JSON d'erreur y serait illisible.
                response.setStatus(429);
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write(
                        "<!doctype html><meta charset=\"utf-8\"><p>Trop de demandes. Réessayez dans une minute.</p>");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}

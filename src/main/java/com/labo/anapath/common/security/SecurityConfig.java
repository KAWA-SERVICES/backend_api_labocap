package com.labo.anapath.common.security;

import com.labo.anapath.common.branch.BranchContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configuration principale de la sécurité Spring Security.
 * <p>
 * Déclare une architecture stateless JWT sans session HTTP ni CSRF.
 * Le contrôle d'accès fin (RBAC par slugs de permissions) est délégué à
 * {@code @PreAuthorize} au niveau des méthodes ({@code @EnableMethodSecurity}).
 * </p>
 *
 * <p><b>Endpoints publics (sans authentification) :</b></p>
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — connexion initiale</li>
 *   <li>{@code POST /api/v1/auth/refresh} — rafraîchissement du token</li>
 *   <li>{@code POST /api/v1/auth/2fa/challenge} — validation du code TOTP</li>
 *   <li>{@code POST /api/v1/auth/forgot-password} — initiation de la réinitialisation du mot de passe</li>
 *   <li>{@code POST /api/v1/auth/reset-password} — réinitialisation effective du mot de passe</li>
 *   <li>{@code POST /api/v1/auth/resend-2fa} — renvoi du code OTP</li>
 *   <li>{@code GET /actuator/health} — sonde de santé</li>
 *   <li>{@code /v3/api-docs/**} et {@code /swagger-ui/**} — documentation OpenAPI</li>
 * </ul>
 * <p>Les fichiers ({@code /api/v1/files/**}) requièrent une authentification JWT valide.</p>
 * <p>Tous les autres endpoints requièrent un token d'accès JWT valide.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final BranchContextFilter branchContextFilter;
    private final CustomUserDetailsService customUserDetailsService;
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Configure la chaîne de filtres de sécurité HTTP.
     * <p>
     * Désactive CSRF (API stateless), impose une politique de session STATELESS
     * et insère le filtre JWT avant le filtre d'authentification standard de Spring.
     * Le filtre de rate limiting est inséré avant le filtre JWT.
     * </p>
     *
     * @param http constructeur de configuration HTTP de Spring Security
     * @return la {@link SecurityFilterChain} construite
     * @throws Exception si la configuration échoue
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(ref -> ref
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sans jeton, 401 et non le 403 que Spring rend à défaut de point
                // d'entrée : le front ne tente le rafraîchissement que sur 401,
                // et 403 reste réservé au droit manquant.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/2fa/challenge").permitAll()
                        .requestMatchers("/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers("/api/v1/auth/reset-password").permitAll()
                        .requestMatchers("/api/v1/auth/resend-2fa").permitAll()
                        // Habillage (logo, nom du labo, favicon) : lu par les écrans
                        // d'authentification, avant qu'un JWT n'existe. Liste blanche de
                        // clés côté contrôleur — voir PublicBrandingController.
                        .requestMatchers("/api/v1/public/branding").permitAll()
                        // Facture téléchargée depuis le lien reçu par SMS. Le client
                        // n'a pas de compte : l'autorisation tient dans le jeton de
                        // l'URL, tiré au sort et expirant — voir PublicInvoiceController.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/invoices/*", "/f/*").permitAll()
                        // Application mobile : deux chemins seulement s'ouvrent sans
                        // jeton, et chacun exige un secret remis hors ligne — le code
                        // d'enrôlement délivré par un administrateur, puis le PIN du
                        // porteur, adossé à un appareil que le serveur peut révoquer.
                        // Rien n'est embarqué dans le binaire : une clé d'API livrée
                        // avec l'application en serait extraite.
                        .requestMatchers("/api/v1/mobile/enroll").permitAll()
                        .requestMatchers("/api/v1/mobile/login").permitAll()
                        // Le renouvellement présente un jeton de rafraîchissement,
                        // qui est lui-même le secret : il ne peut donc pas exiger
                        // un jeton d'accès valide, sinon il ne servirait à rien.
                        .requestMatchers("/api/v1/mobile/refresh").permitAll()
                        // La signalisation des appels s'authentifie à la poignée
                        // de main, jeton en en-tête : le filtre HTTP ne saurait
                        // pas quoi faire d'une requête d'upgrade, et la refuser
                        // ici empêcherait la liaison de s'ouvrir du tout.
                        .requestMatchers("/ws/appels").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Métriques (/actuator/metrics, /actuator/prometheus) : lues
                        // par un Prometheus du réseau Docker, qui n'a pas de JWT.
                        // Réservées aux adresses privées et à la boucle locale ;
                        // nginx, lui, ne relaie pas /actuator/ hors health. L'adresse
                        // vue ici vient de X-Forwarded-For (forward-headers-strategy),
                        // nginx doit donc la poser depuis $remote_addr, jamais depuis
                        // ce que le client envoie — voir docs/supervision.md.
                        .requestMatchers("/actuator/**").access(new WebExpressionAuthorizationManager(
                                "hasIpAddress('127.0.0.0/8') or hasIpAddress('::1') or hasIpAddress('10.0.0.0/8')"
                                + " or hasIpAddress('172.16.0.0/12') or hasIpAddress('192.168.0.0/16')"))
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/files/**").authenticated()
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(branchContextFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Déclare le provider d'authentification DAO utilisant BCrypt pour la vérification
     * du mot de passe et {@link CustomUserDetailsService} pour le chargement des utilisateurs.
     *
     * @return le {@link DaoAuthenticationProvider} configuré
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Déclare l'encodeur de mots de passe BCrypt utilisé pour le hashage lors
     * de la création de compte et pour la vérification lors de l'authentification.
     *
     * @return un {@link BCryptPasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Expose l'{@link AuthenticationManager} du contexte Spring comme bean injectable,
     * nécessaire pour déclencher l'authentification programmatique dans {@link com.labo.anapath.auth.AuthServiceImpl}.
     *
     * @param config configuration d'authentification Spring
     * @return l'{@link AuthenticationManager} actif
     * @throws Exception si la récupération du manager échoue
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<BranchContextFilter> branchContextFilterRegistration(BranchContextFilter filter) {
        FilterRegistrationBean<BranchContextFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}

package com.labo.anapath.common.audit;

import com.labo.anapath.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trace les lectures des données de santé (lot 6) : dossier patient, demande,
 * compte rendu, facture, fichier — et leurs équivalents mobiles.
 *
 * <p>Posé sur les routes de lecture (voir {@code ModulesWebConfig}) ; ne
 * retient qu'une réponse 200 en GET, pour un utilisateur connu. Le travail
 * d'écriture part en asynchrone, après la réponse.</p>
 */
@Component
@RequiredArgsConstructor
public class JournalAccesInterceptor implements HandlerInterceptor {

    private static final String UUID_RE = "([0-9a-fA-F-]{36})";
    private static final Pattern PATIENT = Pattern.compile("^/api/v1/patients/" + UUID_RE + "(?:/profile)?$");
    private static final Pattern DEMANDE = Pattern.compile("^/api/v1/test-orders/" + UUID_RE + "(?:/images|/historique-patient)?$");
    private static final Pattern DOSSIERS_HORS_LIGNE = Pattern.compile("^/api/v1/test-orders/dossiers-hors-ligne$");
    private static final Pattern COMPTE_RENDU = Pattern.compile("^/api/v1/reports/" + UUID_RE + "$");
    private static final Pattern COMPTE_RENDU_PDF = Pattern.compile("^/api/v1/reports/" + UUID_RE + "/pdf$");
    private static final Pattern FACTURE = Pattern.compile("^/api/v1/invoices/" + UUID_RE + "$");
    private static final Pattern FICHIER = Pattern.compile("^/api/v1/files/(.+)$");

    private final JournalAccesService journal;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!"GET".equals(request.getMethod()) || response.getStatus() != 200) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return;

        String uri = request.getRequestURI();
        Matcher m;
        if ((m = PATIENT.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.READ, JournalAcces.Entite.PATIENT, m.group(1));
        } else if ((m = DEMANDE.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.READ, JournalAcces.Entite.TEST_ORDER, m.group(1));
        } else if (DOSSIERS_HORS_LIGNE.matcher(uri).matches()) {
            // Le mobile ouvre plusieurs dossiers en un appel : une ligne par dossier.
            String[] ids = request.getParameterValues("ids");
            for (String id : ids == null ? new String[0] : ids) {
                for (String un : id.split(",")) {
                    if (!un.isBlank()) tracer(request, principal, JournalAcces.Action.READ, JournalAcces.Entite.TEST_ORDER, un.trim());
                }
            }
        } else if ((m = COMPTE_RENDU.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.READ, JournalAcces.Entite.REPORT, m.group(1));
        } else if ((m = COMPTE_RENDU_PDF.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.DOWNLOAD, JournalAcces.Entite.REPORT, m.group(1));
        } else if ((m = FACTURE.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.READ, JournalAcces.Entite.INVOICE, m.group(1));
        } else if ((m = FICHIER.matcher(uri)).matches()) {
            tracer(request, principal, JournalAcces.Action.DOWNLOAD, JournalAcces.Entite.FILE, m.group(1));
        }
    }

    private void tracer(HttpServletRequest request, UserPrincipal principal,
                        JournalAcces.Action action, JournalAcces.Entite entite, String entityId) {
        journal.enregistrer(principal.getId(), principal.getBranchId(), action, entite,
                entityId.length() > 255 ? entityId.substring(0, 255) : entityId,
                request.getRemoteAddr(), empreinte(request.getHeader("User-Agent")));
    }

    /** Le navigateur, sans le garder en clair : une empreinte suffit à distinguer deux postes. */
    public static String empreinte(String userAgent) {
        if (userAgent == null) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(userAgent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    /** Les routes sur lesquelles l'intercepteur est posé. */
    public static final List<String> CHEMINS = List.of(
            "/api/v1/patients/*", "/api/v1/patients/*/profile", "/api/v1/test-orders/*", "/api/v1/test-orders/*/images",
            "/api/v1/test-orders/*/historique-patient", "/api/v1/test-orders/dossiers-hors-ligne",
            "/api/v1/reports/*", "/api/v1/reports/*/pdf", "/api/v1/invoices/*", "/api/v1/files/**");
}

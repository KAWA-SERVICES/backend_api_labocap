package com.labo.anapath.assistant;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.audit.JournalAccesInterceptor;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L'assistant IA : conversations en flux (SSE) et réglages.
 *
 * <p>Les deux routes de flux répondent {@code text/event-stream} sur la requête
 * elle-même, sans dispatch asynchrone (voir {@link AssistantService}). Tout
 * refus survient avant le premier octet et reste une réponse JSON ordinaire.
 * {@code X-Accel-Buffering: no} empêche nginx de retenir le flux.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistant;
    private final ReglagesAssistantService reglages;
    private final RapportAssistantService rapports;
    private final ObjectMapper json;

    public record MessageRequete(@NotBlank @Size(max = 4000) String texte) {}

    public record ConfirmationRequete(@NotNull Boolean accepter) {}

    /** 1 = 👍, -1 = 👎, 0 = retirer l'avis. */
    public record AvisRequete(@jakarta.validation.constraints.Min(-1) @jakarta.validation.constraints.Max(1) int avis,
                              @Size(max = 500) String commentaire) {}

    @GetMapping("/etat")
    @PreAuthorize("hasAnyAuthority('view-assistant', 'manage-assistant')")
    public ResponseEntity<ApiResponse<ReglagesAssistantService.Etat>> etat() {
        return ResponseEntity.ok(ApiResponse.success(reglages.etat()));
    }

    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('view-assistant')")
    public ResponseEntity<ApiResponse<AssistantService.ConversationDto>> ouvrir(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(assistant.ouvrir(contexte(principal))));
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('view-assistant')")
    public ResponseEntity<ApiResponse<List<AssistantService.ConversationDto>>> lister(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(assistant.lister(contexte(principal))));
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('view-assistant')")
    public ResponseEntity<ApiResponse<AssistantService.HistoriqueDto>> historique(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(assistant.historique(contexte(principal), id)));
    }

    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('view-assistant')")
    public void envoyer(@PathVariable UUID id, @Valid @RequestBody MessageRequete corps,
                        @AuthenticationPrincipal UserPrincipal principal,
                        HttpServletRequest requete, HttpServletResponse reponse) throws IOException {
        var p = assistant.preparer(contexte(principal), id, requete.getRemoteAddr(),
                JournalAccesInterceptor.empreinte(requete.getHeader("User-Agent")));
        assistant.envoyer(p, corps.texte(), new FluxDiffere(reponse));
    }

    @PostMapping("/conversations/{id}/confirmations/{appelId}")
    @PreAuthorize("hasAuthority('view-assistant')")
    public void confirmer(@PathVariable UUID id, @PathVariable String appelId,
                          @Valid @RequestBody ConfirmationRequete corps,
                          @AuthenticationPrincipal UserPrincipal principal,
                          HttpServletRequest requete, HttpServletResponse reponse) throws IOException {
        var p = assistant.preparer(contexte(principal), id, requete.getRemoteAddr(),
                JournalAccesInterceptor.empreinte(requete.getHeader("User-Agent")));
        assistant.confirmer(p, appelId, Boolean.TRUE.equals(corps.accepter()), new FluxDiffere(reponse));
    }

    @PutMapping("/conversations/{id}/messages/{seq}/avis")
    @PreAuthorize("hasAuthority('view-assistant')")
    public ResponseEntity<ApiResponse<Void>> avis(@PathVariable UUID id, @PathVariable int seq,
                                                  @Valid @RequestBody AvisRequete corps,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        assistant.donnerAvis(contexte(principal), id, seq, corps.avis(), corps.commentaire());
        return ResponseEntity.ok(ApiResponse.success("Merci pour votre avis", null));
    }

    @GetMapping("/rapports")
    @PreAuthorize("hasAuthority('manage-assistant')")
    public ResponseEntity<ApiResponse<List<RapportAssistantService.RapportDto>>> rapports(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(rapports.lister(principal.getBranchId())));
    }

    @PostMapping("/rapports")
    @PreAuthorize("hasAuthority('manage-assistant')")
    public ResponseEntity<ApiResponse<RapportAssistantService.RapportDto>> genererRapport(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(rapports.generer(principal.getBranchId(), principal.getId())));
    }

    @GetMapping("/reglages")
    @PreAuthorize("hasAuthority('manage-assistant')")
    public ResponseEntity<ApiResponse<ReglagesAssistantService.Vue>> lireReglages() {
        return ResponseEntity.ok(ApiResponse.success(reglages.vue()));
    }

    @PutMapping("/reglages")
    @PreAuthorize("hasAuthority('manage-assistant')")
    public ResponseEntity<ApiResponse<ReglagesAssistantService.Vue>> enregistrerReglages(
            @Valid @RequestBody ReglagesAssistantService.Requete corps,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Réglages enregistrés", reglages.enregistrer(corps, principal.getId())));
    }

    /**
     * Le contexte des outils : l'utilisateur, l'agence revalidée par
     * {@code BranchContextFilter} et ses permissions effectives — celles-là
     * mêmes que vérifient les {@code @PreAuthorize} des routes.
     */
    static OutilAssistant.Contexte contexte(UserPrincipal principal) {
        return new OutilAssistant.Contexte(principal.getId(), principal.getBranchId(),
                principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet()));
    }

    private AssistantService.Emetteur ouvrirLeFlux(HttpServletResponse reponse) throws IOException {
        entetes(reponse);
        PrintWriter ecrivain = reponse.getWriter();
        return (evenement, donnees) -> ecrire(ecrivain, reponse, evenement, donnees);
    }

    private void entetes(HttpServletResponse reponse) {
        reponse.setStatus(200);
        reponse.setContentType("text/event-stream");
        reponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        reponse.setHeader("Cache-Control", "no-cache");
        reponse.setHeader("X-Accel-Buffering", "no");
    }

    private void ecrire(PrintWriter ecrivain, HttpServletResponse reponse, String evenement, Object donnees)
            throws IOException {
        ecrivain.write("event: " + evenement + "\ndata: " + json.writeValueAsString(donnees) + "\n\n");
        ecrivain.flush();
        if (ecrivain.checkError()) throw new IOException("navigateur déconnecté");
        reponse.flushBuffer();
    }

    /**
     * Un flux qui n'ouvre la réponse qu'au premier événement : une exception
     * levée avant (confirmation déjà tranchée, erreur de lecture de
     * l'historique) reste une réponse JSON ordinaire.
     */
    private final class FluxDiffere implements AssistantService.Emetteur {
        private final HttpServletResponse reponse;
        private AssistantService.Emetteur flux;

        FluxDiffere(HttpServletResponse reponse) {
            this.reponse = reponse;
        }

        @Override
        public void envoyer(String evenement, Object donnees) throws IOException {
            if (flux == null) flux = ouvrirLeFlux(reponse);
            flux.envoyer(evenement, donnees);
        }
    }
}

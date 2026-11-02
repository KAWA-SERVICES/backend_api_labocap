package com.labo.anapath.assistant;

import com.labo.anapath.common.security.ChiffreurDeSecrets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Les réglages de l'assistant et la consommation du mois. */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ReglagesAssistantService {

    private final ReglagesAssistantRepository depot;
    private final MessageAssistantRepository messages;
    private final RapportAssistantRepository rapports;
    private final ChiffreurDeSecrets chiffreur;

    /** Ce que voit l'écran des réglages : jamais la clé, seulement si elle existe. */
    public record Vue(boolean actif, String fournisseur, String modele, boolean caviardage,
                      Long quotaMensuelJetons, boolean cleApiConfiguree, long consommeCeMois) {}

    /** Ce que voit un utilisateur : l'assistant répond-il ? */
    public record Etat(boolean actif, String fournisseur, String modele) {}

    public record Requete(
            boolean actif,
            @NotBlank @Pattern(regexp = "ANTHROPIC|OPENAI") String fournisseur,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String modele,
            boolean caviardage,
            @PositiveOrZero Long quotaMensuelJetons,
            /** Absente : la clé enregistrée ne change pas. */
            @Size(max = 500) String cleApi) {}

    @Transactional(readOnly = true)
    public ReglagesAssistant courants() {
        return depot.findById(ReglagesAssistant.ID).orElseGet(ReglagesAssistant::new);
    }

    @Transactional(readOnly = true)
    public Vue vue() {
        ReglagesAssistant r = courants();
        return new Vue(r.isActif(), r.getFournisseur(), r.getModele(), r.isCaviardage(),
                r.getQuotaMensuelJetons(), r.getCleApi() != null && !r.getCleApi().isBlank(), consommeCeMois());
    }

    @Transactional(readOnly = true)
    public Etat etat() {
        ReglagesAssistant r = courants();
        return new Etat(pret(r), r.getFournisseur(), r.getModele());
    }

    static boolean pret(ReglagesAssistant r) {
        return r.isActif() && r.getCleApi() != null && !r.getCleApi().isBlank();
    }

    @Transactional
    public Vue enregistrer(Requete req, UUID auteur) {
        ReglagesAssistant r = depot.findById(ReglagesAssistant.ID).orElseGet(ReglagesAssistant::new);
        if (req.cleApi() != null && !req.cleApi().isBlank()) {
            // Sans clé de chiffrement configurée, le convertisseur écrirait la clé
            // en clair : on refuse plutôt que de la stocker ainsi.
            if (!chiffreur.estActif()) {
                throw new AssistantException(HttpStatus.UNPROCESSABLE_CONTENT, "chiffrement_absent",
                        "Le chiffrement des secrets n'est pas configuré sur le serveur (SECRETS_KEY) : "
                                + "la clé API ne peut pas être enregistrée.");
            }
            r.setCleApi(req.cleApi().strip());
        }
        if (req.actif() && (r.getCleApi() == null || r.getCleApi().isBlank())) {
            throw new AssistantException(HttpStatus.UNPROCESSABLE_CONTENT, "cle_absente",
                    "Saisissez une clé API avant d'activer l'assistant.");
        }
        r.setActif(req.actif());
        r.setFournisseur(req.fournisseur());
        r.setModele(req.modele());
        if (r.isCaviardage() != req.caviardage()) {
            log.warn("Masquage de l'assistant {} par l'utilisateur {}", req.caviardage() ? "activé" : "DÉSACTIVÉ", auteur);
        }
        r.setCaviardage(req.caviardage());
        r.setQuotaMensuelJetons(req.quotaMensuelJetons());
        r.setUpdatedAt(LocalDateTime.now());
        r.setUpdatedBy(auteur);
        depot.save(r);
        return vue();
    }

    public long consommeCeMois() {
        LocalDateTime debutDuMois = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return messages.jetonsDepuis(debutDuMois) + rapports.jetonsDepuis(debutDuMois);
    }
}

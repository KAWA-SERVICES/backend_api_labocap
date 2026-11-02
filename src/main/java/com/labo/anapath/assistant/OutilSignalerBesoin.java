package com.labo.anapath.assistant;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.assistant.OutilAssistant.Resultat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * L'outil par lequel l'assistant signale ce qu'il n'a pas su faire : la matière
 * première du rapport d'amélioration.
 *
 * <p>Le moteur lui passe les arguments <em>tels que le modèle les a écrits</em>,
 * donc masqués, sans les rétablir (voir {@code AssistantService#boucle}) : un nom
 * de patient ne se retrouve pas en clair dans le rapport.</p>
 */
@Configuration
public class OutilSignalerBesoin {

    static final String NOM = "signaler_besoin";

    public record Args(
            @NotBlank @Size(max = 300) String demande,
            @NotBlank @Pattern(regexp = "outil_manquant|donnee_absente|droit_manquant|autre") String raison) {}

    @Bean
    OutilAssistant signalerBesoin(BesoinRepository besoins) {
        return OutilAssistant.lecture(NOM, "Besoin noté pour l'équipe",
                "À appeler quand tu ne peux pas répondre à une demande : aucun outil ne la couvre (outil_manquant), "
                        + "la donnée n'est pas exposée (donnee_absente), l'utilisateur n'a pas le droit (droit_manquant), "
                        + "ou autre. Reformule la demande en une phrase générale, sans nom, numéro ni code ; "
                        + "les jetons masqués éventuels restent tels quels. Ne modifie aucune donnée du laboratoire.",
                """
                {"type":"object","properties":{
                  "demande":{"type":"string","description":"La demande reformulée, une phrase, sans donnée personnelle."},
                  "raison":{"type":"string","enum":["outil_manquant","donnee_absente","droit_manquant","autre"]}},
                 "required":["demande","raison"],"additionalProperties":false}""",
                "view-assistant", Args.class, (ctx, a) -> {
                    Besoin b = new Besoin();
                    b.setBranchId(ctx.branchId());
                    b.setUserId(ctx.userId());
                    b.setDemande(a.demande().strip());
                    b.setRaison(a.raison());
                    b.setCreatedAt(LocalDateTime.now());
                    besoins.save(b);
                    return Resultat.de(Map.of("enregistre", true), List.of());
                });
    }
}

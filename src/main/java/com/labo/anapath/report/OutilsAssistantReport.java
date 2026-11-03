package com.labo.anapath.report;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.assistant.OutilAssistant.Lecture;
import com.labo.anapath.common.assistant.OutilAssistant.Resultat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Les outils de l'assistant IA sur les comptes rendus.
 *
 * <p>Métadonnées seulement : état, dates, signataires. Le texte du compte rendu
 * (macroscopie, microscopie, conclusion, commentaires) n'est jamais projeté —
 * c'est le cœur du dossier médical, et rien ne saurait le masquer. Pour le lire,
 * l'assistant ouvre la page du compte rendu, où l'accès est contrôlé et tracé
 * comme d'ordinaire.</p>
 *
 * <p>La recherche par mot clé ({@code compte_rendu_rechercher}) cherche dans ce
 * texte côté serveur — celle de l'écran « Recherche globale » — et ne renvoie
 * que les dossiers qui le contiennent : seul le mot cherché part chez le
 * fournisseur, jamais le texte autour.</p>
 */
@Configuration
public class OutilsAssistantReport {

    public record ArgsCompteRendu(@NotBlank @Size(max = 50) String codeDemande) {}

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "codeDemande":{"type":"string","description":"Le code de la demande d'examen dont c'est le compte rendu."}},
             "required":["codeDemande"],"additionalProperties":false}""";

    @Bean
    OutilAssistant compteRenduEtat(ReportService comptesRendus) {
        return OutilAssistant.lecture("compte_rendu_etat", "Compte rendu consulté",
                "L'état du compte rendu d'une demande — statut DRAFT = pas terminé (en cours, en "
                        + "attente, pas encore validé ; « En attente » à l'écran), VALIDATED = terminé et validé, DELIVERED = remis ; qui l'a validé et quand ; remis ou non, et "
                        + "quand. Ne donne pas le texte du compte rendu.",
                SCHEMA, "view-reports", ArgsCompteRendu.class, (ctx, a) -> {
                    ReportDetailDto r = comptesRendus.findDetailByTestOrderCode(a.codeDemande().strip(), ctx.branchId());
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("reportId", r.id());
                    d.put("code", r.code());
                    d.put("testOrderId", r.testOrderId());
                    d.put("codeDemande", r.testOrderCode());
                    d.put("patientName", r.patientName());
                    d.put("statut", r.status());
                    d.put("medecin", r.assignedToName());
                    d.put("reluPar", r.reviewedByName());
                    d.put("validePar", r.validatedByName());
                    d.put("signeLe", r.signatureDate());
                    d.put("remis", r.isDelivered());
                    d.put("remisLe", r.deliveryDate());
                    d.put("patientPrevenu", r.isCalled());
                    return Resultat.de(d, List.of(Lecture.compteRendu(r.id())));
                });
    }

    @Bean
    OutilAssistant compteRenduOuvrir(ReportService comptesRendus) {
        return OutilAssistant.lecture("compte_rendu_ouvrir", "Ouverture du compte rendu",
                "Ouvre à l'écran le compte rendu d'une demande, pour que l'utilisateur le lise. "
                        + "Pour « ouvre-moi le compte rendu de … ».",
                SCHEMA, "view-reports", ArgsCompteRendu.class, (ctx, a) -> {
                    // Lu ici pour vérifier qu'il est de l'agence ; la page, en le
                    // chargeant, laisse la trace ordinaire au journal des accès.
                    ReportDetailDto r = comptesRendus.findDetailByTestOrderCode(a.codeDemande().strip(), ctx.branchId());
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("reportId", r.id());
                    d.put("codeDemande", r.testOrderCode());
                    d.put("statut", r.status());
                    d.put("ouvert", true);
                    return new Resultat(d, List.of(), Map.of("naviguer", "/reports/" + r.id()));
                });
    }

    static final int RESULTATS_MAX = 20;

    public record ArgsRecherche(
            @NotBlank @Size(min = 2, max = 100) String recherche,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String depuis,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String jusqua,
            Boolean urgent) {}

    @Bean
    OutilAssistant compteRenduRechercher(ReportService comptesRendus) {
        // « recherche » et non « motCle » : la trace des appels ne conserve pas ce
        // champ (PointDePassage), il peut porter un nom de patient.
        return OutilAssistant.lecture("compte_rendu_rechercher", "Recherche dans les comptes rendus",
                "Cherche un mot ou une expression dans le texte des comptes rendus de l'agence (macroscopie, "
                        + "microscopie, compléments), sans accents ni casse, comme la Recherche globale. Trouve aussi "
                        + "un code de compte rendu, de demande ou de patient. Renvoie le nombre total et les "
                        + RESULTATS_MAX + " plus récents ; jamais le texte lui-même. Mot exact, pas de recherche par sens : "
                        + "pour un synonyme, refais une recherche. Pour « les CR qui parlent de … », « combien de … ».",
                """
                {"type":"object","properties":{
                  "recherche":{"type":"string","description":"Le mot ou l'expression à trouver, tel que l'utilisateur l'a écrit."},
                  "depuis":{"type":"string","description":"Date de création au plus tôt, AAAA-MM-JJ."},
                  "jusqua":{"type":"string","description":"Date de création au plus tard, AAAA-MM-JJ."},
                  "urgent":{"type":"boolean","description":"true : seulement les demandes urgentes."}},
                 "required":["recherche"],"additionalProperties":false}""",
                "view-reports", ArgsRecherche.class, (ctx, a) -> {
                    var page = comptesRendus.globalSearch(ctx.branchId(), 0, RESULTATS_MAX,
                            null, null, null, null, null, null,
                            a.depuis(), a.jusqua(), a.recherche().strip(), a.urgent());
                    List<Map<String, Object>> lignes = new ArrayList<>();
                    List<Lecture> lus = new ArrayList<>();
                    for (ReportGlobalSearchRowDto r : page.content()) {
                        Map<String, Object> l = new LinkedHashMap<>();
                        l.put("reportId", r.reportId());
                        l.put("code", r.codeReport());
                        l.put("codeDemande", r.codeExamen());
                        l.put("typeExamen", r.typeExamen());
                        l.put("creeLe", r.dateCreation());
                        l.put("patientId", r.patientId());
                        l.put("patientFirstname", r.patientFirstname());
                        l.put("patientLastname", r.patientLastname());
                        l.put("prescripteur", r.doctorName());
                        l.put("hopital", r.hospitalName());
                        l.put("urgent", r.isUrgent());
                        lignes.add(l);
                        if (r.reportId() != null) lus.add(Lecture.compteRendu(r.reportId()));
                    }
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("total", page.totalElements());
                    d.put("affiches", lignes.size());
                    d.put("comptesRendus", lignes);
                    return Resultat.de(d, lus);
                });
    }
}

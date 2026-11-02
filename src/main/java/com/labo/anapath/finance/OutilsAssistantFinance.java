package com.labo.anapath.finance;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.assistant.OutilAssistant.Lecture;
import com.labo.anapath.common.assistant.OutilAssistant.Resultat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les outils de l'assistant IA sur les factures et la caisse — en lecture seule.
 *
 * <p>Sous {@code view-invoices}, comme les routes équivalentes. Les montants
 * partent en clair (ce ne sont pas des données de santé) ; le nom du patient,
 * sous {@code patientName}, est masqué.</p>
 */
@Configuration
public class OutilsAssistantFinance {

    public record ArgsImpayees(LocalDate debut, LocalDate fin, @Size(max = 100) String recherche,
                               @Min(1) @Max(30) Integer limite) {}

    @Bean
    OutilAssistant facturesImpayees(InvoiceService factures) {
        return OutilAssistant.lecture("factures_impayees", "Factures impayées consultées",
                "Les factures non payées de l'agence, sur une période (par défaut toutes), éventuellement filtrées par "
                        + "code ou nom de patient. Donne aussi leur nombre total.",
                """
                {"type":"object","properties":{
                  "debut":{"type":"string","format":"date","description":"Factures datées de ce jour au plus tôt (AAAA-MM-JJ)."},
                  "fin":{"type":"string","format":"date","description":"Factures datées de ce jour au plus tard."},
                  "recherche":{"type":"string","description":"Code de facture ou de demande, nom du patient."},
                  "limite":{"type":"integer","minimum":1,"maximum":30,"description":"15 par défaut."}},
                 "additionalProperties":false}""",
                "view-invoices", ArgsImpayees.class, (ctx, a) -> {
                    var page = factures.findAll(0, a.limite() == null ? 15 : a.limite(), ctx.branchId(), false, null,
                            a.debut(), a.fin(), a.recherche());
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("total", page.totalElements());
                    d.put("factures", page.content().stream().map(OutilsAssistantFinance::resume).toList());
                    return Resultat.de(d, List.of());
                });
    }

    public record ArgsFacture(@NotNull UUID factureId) {}

    @Bean
    OutilAssistant factureDetail(InvoiceService factures) {
        return OutilAssistant.lecture("facture_detail", "Facture consultée",
                "Le détail d'une facture : montant, payée ou non, mode de paiement, échéance, facture normalisée.",
                """
                {"type":"object","properties":{
                  "factureId":{"type":"string","description":"L'identifiant de la facture (factures_impayees)."}},
                 "required":["factureId"],"additionalProperties":false}""",
                "view-invoices", ArgsFacture.class, (ctx, a) -> {
                    InvoiceResponseDto f = factures.findById(a.factureId(), ctx.branchId());
                    Map<String, Object> d = resume(f);
                    d.put("modePaiement", f.payment());
                    d.put("echeance", f.dueDate());
                    d.put("factureNormalisee", f.codeNormalise());
                    return Resultat.de(d, List.of(Lecture.facture(f.id())));
                });
    }

    @Bean
    OutilAssistant recetteDuJour(InvoiceService factures) {
        return OutilAssistant.lecture("recette_du_jour", "Recette du jour consultée",
                "Le total encaissé aujourd'hui dans l'agence.",
                """
                {"type":"object","properties":{},"additionalProperties":false}""",
                "view-invoices", Object.class, (ctx, a) -> Resultat.de(Map.of(
                        "date", LocalDate.now(),
                        "totalEncaisse", factures.getTotalEncashedToday(ctx.branchId())), List.of()));
    }

    static Map<String, Object> resume(InvoiceResponseDto f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factureId", f.id());
        m.put("code", f.code());
        m.put("codeDemande", f.testOrderCode());
        m.put("patientName", f.patientName());
        m.put("clientName", f.clientName());
        m.put("date", f.date());
        m.put("total", f.total());
        m.put("payee", Boolean.TRUE.equals(f.paid()));
        return m;
    }
}

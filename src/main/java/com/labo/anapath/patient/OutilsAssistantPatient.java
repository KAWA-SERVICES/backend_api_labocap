package com.labo.anapath.patient;

import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.assistant.OutilAssistant.Lecture;
import com.labo.anapath.common.assistant.OutilAssistant.Resultat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les outils de l'assistant IA sur les patients.
 *
 * <p>Identité réduite au nécessaire pour reconnaître un patient — nom, code,
 * genre ; pas l'âge, qui avec le genre et la date du prélèvement suffirait à
 * réidentifier un patient derrière ses jetons — sous des clés que le masquage reconnaît ({@code firstname},
 * {@code lastname}, {@code codePatient}, {@code patientId}). Ni adresse, ni
 * profession, ni téléphone, ni courriel : le modèle n'en a pas l'usage.</p>
 */
@Configuration
public class OutilsAssistantPatient {

    public record ArgsChercher(@NotBlank @Size(min = 2, max = 100) String recherche,
                               @Min(1) @Max(20) Integer limite) {}

    @Bean
    OutilAssistant patientChercher(PatientService patients) {
        return OutilAssistant.lecture("patient_chercher", "Patients consultés",
                "Cherche des patients de l'agence par nom ou par code patient.",
                """
                {"type":"object","properties":{
                  "recherche":{"type":"string","description":"Nom, prénom ou code du patient (2 caractères au moins)."},
                  "limite":{"type":"integer","minimum":1,"maximum":20,"description":"10 par défaut."}},
                 "required":["recherche"],"additionalProperties":false}""",
                "view-patients", ArgsChercher.class, (ctx, a) -> Resultat.de(
                        patients.findAll(0, a.limite() == null ? 10 : a.limite(), a.recherche(), ctx.branchId())
                                .content().stream().map(OutilsAssistantPatient::identite).toList(),
                        List.of()));
    }

    public record ArgsPatient(@NotNull UUID patientId) {}

    @Bean
    OutilAssistant patientHistorique(PatientService patients) {
        return OutilAssistant.lecture("patient_historique", "Dossier patient consulté",
                "L'historique d'un patient au laboratoire, dans l'agence : nombre de demandes (en cours, terminées), "
                        + "liste des demandes avec type d'examen, date de prélèvement et état, et, si l'utilisateur "
                        + "voit les factures, ce qui a été facturé et payé. Pour « est-il déjà venu », « ses antécédents chez nous ». "
                        + "Obtiens d'abord patientId avec patient_chercher.",
                """
                {"type":"object","properties":{
                  "patientId":{"type":"string","description":"L'identifiant du patient (patient_chercher)."}},
                 "required":["patientId"],"additionalProperties":false}""",
                "view-patients", ArgsPatient.class, (ctx, a) -> {
                    PatientProfileDto p = patients.getProfile(a.patientId(), ctx.branchId());
                    Map<String, Object> d = new LinkedHashMap<>(identite(p.patient()));
                    d.put("totalDemandes", p.totalOrders());
                    d.put("enCours", p.pendingOrders());
                    d.put("terminees", p.completedOrders());
                    d.put("demandes", p.recentOrders().stream().map(o -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("testOrderId", o.id());
                        m.put("code", o.code());
                        m.put("typeExamen", o.typeExamen());
                        m.put("statut", o.status());
                        m.put("prelevementDate", o.prelevementDate());
                        m.put("recueLe", o.createdAt());
                        return m;
                    }).toList());
                    // La facturation suit son propre droit, comme à l'écran.
                    if (ctx.permissions().contains("view-invoices")) {
                        d.put("totalFacture", p.totalInvoiced());
                        d.put("totalPaye", p.totalPaid());
                        d.put("resteAPayer", p.totalUnpaid());
                    }
                    return Resultat.de(d, List.of(Lecture.patient(a.patientId())));
                });
    }

    static Map<String, Object> identite(PatientResponseDto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", p.id());
        m.put("codePatient", p.code());
        m.put("firstname", p.firstname());
        m.put("lastname", p.lastname());
        m.put("genre", p.genre());
        return m;
    }
}

package com.labo.anapath.testorder;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.assistant.OutilAssistant;
import com.labo.anapath.common.assistant.OutilAssistant.Confirmation;
import com.labo.anapath.common.assistant.OutilAssistant.Lecture;
import com.labo.anapath.common.assistant.OutilAssistant.Resultat;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.user.UserRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les outils de l'assistant IA sur les demandes d'examen et la file du médecin.
 *
 * <p>Minces : chaque outil appelle un service existant, avec l'agence et
 * l'utilisateur du contexte, jamais ceux que propose le modèle. Les
 * projections ne reprennent aucun texte libre (notes de lot, notes de
 * demande) : le modèle n'a pas à les lire, et le masquage ne saurait pas les
 * nettoyer. Les noms de patient sont rangés sous des clés que le masquage
 * reconnaît ({@code patientName}).</p>
 */
@Configuration
public class OutilsAssistantTestOrder {

    private static final String VOIR_FILE = "view-test-order-assignments";

    // -------------------------------------------------------------------------
    // La file du médecin connecté
    // -------------------------------------------------------------------------

    public record ArgsResume(@Min(2000) @Max(2100) Integer annee) {}

    @Bean
    OutilAssistant fileResume(TestOrderAssignmentService affectations) {
        return OutilAssistant.lecture("file_resume", "Votre file consultée",
                "Les chiffres de la file de l'utilisateur connecté (le médecin) : dossiers à traiter, en relecture, "
                        + "terminés, urgents, en retard, et le reliquat des années précédentes. "
                        + "Pour « combien il me reste », « où en est ma file ».",
                """
                {"type":"object","properties":{
                  "annee":{"type":"integer","description":"Année d'ouverture des dossiers ; par défaut l'année en cours."}},
                 "additionalProperties":false}""",
                VOIR_FILE, ArgsResume.class, (ctx, a) -> {
                    int annee = a.annee() != null ? a.annee() : LocalDate.now().getYear();
                    FiltreFileDuMedecin filtre = new FiltreFileDuMedecin(annee, null, null, null, null, null,
                            null, null, null, Discipline.PATHOLOGY);
                    ResumeFileDto r = affectations.resumeDeLaFile(ctx.userId(), filtre);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("annee", annee);
                    d.put("aTraiter", r.aTraiter());
                    d.put("enRelecture", r.enRelecture());
                    d.put("termines", r.termine());
                    d.put("urgents", r.urgents());
                    d.put("enRetard", r.enRetard());
                    d.put("anneesPrecedentes", affectations.arriereDuMedecin(ctx.userId(), annee, Discipline.PATHOLOGY));
                    return Resultat.de(d, List.of());
                });
    }

    public record ArgsPriorite(@Min(1) @Max(20) Integer limite) {}

    @Bean
    OutilAssistant filePriorite(TestOrderAssignmentService affectations) {
        return OutilAssistant.lecture("file_priorite", "Votre file triée par priorité",
                "Les dossiers restant à traiter de l'utilisateur connecté, dans l'ordre où les prendre : "
                        + "urgents d'abord, puis en retard (du plus en retard au moins en retard), puis par date de lot "
                        + "la plus ancienne. Pour « par quoi je commence », « quelles sont mes priorités ».",
                """
                {"type":"object","properties":{
                  "limite":{"type":"integer","minimum":1,"maximum":20,"description":"Nombre de dossiers ; 10 par défaut."}},
                 "additionalProperties":false}""",
                VOIR_FILE, ArgsPriorite.class, (ctx, a) -> {
                    var lignes = affectations.prioritesDeLaFile(ctx.userId(),
                            a.limite() == null ? 10 : a.limite(), Discipline.PATHOLOGY);
                    List<Map<String, Object>> d = new ArrayList<>();
                    List<Lecture> lus = new ArrayList<>();
                    int rang = 1;
                    for (DemandeDuMedecinDto l : lignes) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("rang", rang++);
                        m.put("testOrderId", l.testOrderId());
                        m.put("code", l.code());
                        m.put("patientName", l.patientName());
                        m.put("raison", l.urgent() ? "Urgent" : l.enRetard() ? "En retard" : "Plus ancien lot");
                        m.put("avancement", l.avancement());
                        m.put("lot", l.assignmentCode());
                        m.put("dateLot", l.assignmentDate());
                        d.add(m);
                        if (l.testOrderId() != null) lus.add(Lecture.demande(l.testOrderId()));
                    }
                    return Resultat.de(d, lus);
                });
    }

    // -------------------------------------------------------------------------
    // Les demandes de l'agence
    // -------------------------------------------------------------------------

    public record ArgsChercher(
            @Size(max = 100) String recherche,
            Boolean urgent,
            TestOrderStatus statut,
            @jakarta.validation.constraints.Pattern(regexp = "DRAFT|PENDING_REVIEW|VALIDATED") String statutCompteRendu,
            Boolean nonAffectees,
            LocalDate creeesAvant,
            @Min(1) @Max(30) Integer limite) {}

    @Bean
    OutilAssistant demandeChercher(TestOrderService demandes) {
        return OutilAssistant.lecture("demande_chercher", "Demandes d'examen consultées",
                "Cherche des demandes d'examen de l'agence. Filtres combinables : texte (code de la demande ou nom "
                        + "du patient), urgentes, état de la demande, état du compte rendu, non affectées à un médecin, "
                        + "enregistrées avant une date. Les plus récentes d'abord.",
                """
                {"type":"object","properties":{
                  "recherche":{"type":"string","description":"Code de la demande ou nom du patient."},
                  "urgent":{"type":"boolean","description":"Seulement les demandes marquées urgentes."},
                  "statut":{"type":"string","enum":["PENDING","VALIDATED","DELIVERED","CANCELLED"],
                            "description":"PENDING = enregistrée, VALIDATED = validée (compte rendu ouvert), DELIVERED = remise."},
                  "statutCompteRendu":{"type":"string","enum":["DRAFT","PENDING_REVIEW","VALIDATED"],
                            "description":"DRAFT = compte rendu pas terminé : en cours, en attente, pas encore validé (« En attente » à l'écran Comptes rendus). VALIDATED = terminé et validé. PENDING_REVIEW ne sert qu'en biologie."},
                  "nonAffectees":{"type":"boolean","description":"Seulement les demandes qu'aucun médecin n'a encore."},
                  "creeesAvant":{"type":"string","format":"date","description":"Enregistrées au plus tard ce jour (AAAA-MM-JJ) : pour les retards."},
                  "limite":{"type":"integer","minimum":1,"maximum":30,"description":"15 par défaut."}},
                 "additionalProperties":false}""",
                "view-test-orders", ArgsChercher.class, (ctx, a) -> {
                    TestOrderFilterDto f = new TestOrderFilterDto();
                    f.setSearch(a.recherche());
                    f.setIsUrgent(a.urgent());
                    f.setStatus(a.statut());
                    f.setReportStatus(a.statutCompteRendu());
                    f.setTo(a.creeesAvant());
                    int limite = a.limite() == null ? 15 : a.limite();
                    boolean nonAffectees = Boolean.TRUE.equals(a.nonAffectees());
                    // ponytail: « non affectées » filtré sur une page élargie, faute de critère dans le filtre ;
                    // un critère SQL si les volumes le demandent
                    var page = demandes.findAll(0, nonAffectees ? 100 : limite, f, ctx.branchId());
                    List<Map<String, Object>> d = page.content().stream()
                            .filter(o -> !nonAffectees || (o.assignedToUserId() == null && o.attribuateDoctorId() == null))
                            .limit(limite)
                            .map(OutilsAssistantTestOrder::resumeDemande)
                            .toList();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("total", nonAffectees ? d.size() : page.totalElements());
                    r.put("demandes", d);
                    return Resultat.de(r, List.of());
                });
    }

    static Map<String, Object> resumeDemande(TestOrderResponseDto o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("testOrderId", o.id());
        m.put("code", o.code());
        m.put("patientName", ((o.patientFirstname() == null ? "" : o.patientFirstname()) + " "
                + (o.patientLastname() == null ? "" : o.patientLastname())).strip());
        m.put("typeExamen", o.typeOrderTitle());
        m.put("statut", o.status());
        m.put("urgent", Boolean.TRUE.equals(o.isUrgent()));
        m.put("prelevementDate", o.prelevementDate());
        m.put("recueLe", o.createdAt());
        m.put("medecin", o.assignedUserName());
        m.put("reportId", o.reportId());
        m.put("statutCompteRendu", o.reportStatus());
        return m;
    }

    public record ArgsDemande(@NotBlank @Size(max = 50) String codeDemande) {}

    @Bean
    OutilAssistant demandeHistorique(TestOrderAssignmentService affectations, TestOrderRepository depot) {
        return OutilAssistant.lecture("demande_historique", "Historique de la demande consulté",
                "Comment une demande a été traitée : à quels médecins elle a été confiée, dans quels lots, quand, "
                        + "par qui, et où en est le médecin. À compléter par compte_rendu_etat pour la validation et la remise.",
                """
                {"type":"object","properties":{
                  "codeDemande":{"type":"string","description":"Le code de la demande d'examen."}},
                 "required":["codeDemande"],"additionalProperties":false}""",
                VOIR_FILE, ArgsDemande.class, (ctx, a) -> {
                    TestOrder o = parCode(depot, a.codeDemande(), ctx.branchId());
                    HistoriqueAffectationDto h = affectations.historiqueDe(o.getId(), ctx.branchId());
                    List<Map<String, Object>> etapes = h.etapes().stream().map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("lot", e.codeAffectation());
                        m.put("dateLot", e.dateAffectation());
                        m.put("medecin", e.medecin());
                        m.put("confieePar", e.confieePar());
                        m.put("confieeLe", e.confieeLe());
                        m.put("remplaceeLe", e.remplaceeLe());
                        m.put("statutDuMedecin", e.statutDuMedecin());
                        return m;
                    }).toList();
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("testOrderId", o.getId());
                    d.put("code", o.getCode());
                    d.put("statut", o.getStatus());
                    d.put("affectations", etapes);
                    return Resultat.de(d, List.of(Lecture.demande(o.getId())));
                });
    }

    // -------------------------------------------------------------------------
    // Affecter une demande à un médecin — ÉCRITURE, soumise à confirmation
    // -------------------------------------------------------------------------

    @Bean
    OutilAssistant medecinsLister(UserRepository utilisateurs) {
        return OutilAssistant.lecture("medecins_lister", "Médecins consultés",
                "La liste des médecins pathologistes actifs, avec leur identifiant, pour affecter une demande.",
                """
                {"type":"object","properties":{},"additionalProperties":false}""",
                "manage-test-order-assignments", Object.class, (ctx, a) -> Resultat.de(
                        utilisateurs.findSignataires().stream().filter(u -> u.isActive())
                                .map(u -> Map.of("medecinId", u.getId(),
                                        "medecin", ((u.getFirstname() == null ? "" : u.getFirstname()) + " "
                                                + (u.getLastname() == null ? "" : u.getLastname())).strip()))
                                .toList(), List.of()));
    }

    public record ArgsAffecter(@NotBlank @Size(max = 50) String codeDemande, @NotNull UUID medecinId) {}

    @Bean
    OutilAssistant demandeAffecterAuMedecin(TestOrderAssignmentService affectations, TestOrderRepository depot,
                                            TestOrderService demandes, UserRepository utilisateurs) {
        return OutilAssistant.ecriture("demande_affecter_au_medecin", "Affectation de la demande",
                "Confie une demande d'examen à un médecin pathologiste (dans son lot du jour). L'utilisateur voit une "
                        + "carte de confirmation et décide : appelle cet outil directement, sans demander d'accord par écrit. "
                        + "Obtiens d'abord l'identifiant du médecin avec medecins_lister.",
                """
                {"type":"object","properties":{
                  "codeDemande":{"type":"string","description":"Le code de la demande d'examen."},
                  "medecinId":{"type":"string","format":"uuid","description":"L'identifiant du médecin (medecins_lister)."}},
                 "required":["codeDemande","medecinId"],"additionalProperties":false}""",
                "manage-test-order-assignments", ArgsAffecter.class,
                (ctx, a) -> {
                    TestOrder o = parCode(depot, a.codeDemande(), ctx.branchId());
                    // L'utilisateur a confirmé la carte, qui nommait le médecin en place :
                    // la réaffectation est donc voulue. Si le dossier a changé de mains
                    // entre-temps, le résultat le dit (etaitChez) plutôt que de le taire.
                    String etaitChez = medecinEnPlace(affectations, o.getId(), ctx.branchId());
                    var ligne = affectations.affecterAuMedecin(o.getId(), a.medecinId(), true, ctx.branchId());
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("testOrderId", o.getId());
                    d.put("code", o.getCode());
                    d.put("medecin", nomDuMedecin(utilisateurs, a.medecinId()));
                    if (etaitChez != null) d.put("etaitChez", etaitChez);
                    d.put("affectee", ligne != null);
                    return Resultat.de(d, List.of(Lecture.demande(o.getId())));
                },
                (ctx, a) -> {
                    TestOrder o = parCode(depot, a.codeDemande(), ctx.branchId());
                    // Par le service (transactionnel) : le type d'examen est une relation paresseuse.
                    String type = demandes.findById(o.getId(), ctx.branchId()).typeOrderTitle();
                    List<Confirmation.Ligne> lignes = new ArrayList<>();
                    lignes.add(new Confirmation.Ligne("Demande", o.getCode() + (type == null ? "" : " · " + type)));
                    lignes.add(new Confirmation.Ligne("Médecin", nomDuMedecin(utilisateurs, a.medecinId())));
                    String enPlace = medecinEnPlace(affectations, o.getId(), ctx.branchId());
                    if (enPlace != null) lignes.add(new Confirmation.Ligne("Actuellement chez", enPlace));
                    return new Confirmation("Affecter une demande d'examen", lignes);
                });
    }

    private static String medecinEnPlace(TestOrderAssignmentService affectations, UUID demandeId, UUID branchId) {
        return affectations.historiqueDe(demandeId, branchId).etapes().stream()
                .filter(e -> e.remplaceeLe() == null)
                .reduce((premier, second) -> second)
                .map(e -> e.medecin()).orElse(null);
    }

    private static TestOrder parCode(TestOrderRepository depot, String code, UUID branchId) {
        return depot.findByCodeAndBranchId(code.strip(), branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Aucune demande " + code + " dans cette agence."));
    }

    private static String nomDuMedecin(UserRepository utilisateurs, UUID id) {
        return utilisateurs.findSignataires().stream().filter(u -> u.getId().equals(id)).findFirst()
                .map(u -> "Dr " + ((u.getFirstname() == null ? "" : u.getFirstname()) + " "
                        + (u.getLastname() == null ? "" : u.getLastname())).strip())
                .orElseThrow(() -> new com.labo.anapath.common.exception.BusinessException(
                        "Ce destinataire n'est pas un médecin actif du laboratoire."));
    }
}

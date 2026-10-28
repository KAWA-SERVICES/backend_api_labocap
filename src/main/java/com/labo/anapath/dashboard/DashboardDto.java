package com.labo.anapath.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.report.IssueRappel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class DashboardDto {

    // KPIs admin
    /**
     * Cartes et donut du tableau de bord admin.
     *
     * <p>{@code finishTest} / {@code noFinishTest} alimentent le graphique
     * « STATUT D'EXAMENS » et reprennent le {@code $totalByStatus} de Laravel
     * (bons d'examen joints à leur compte rendu, regroupés par statut).</p>
     *
     * <p>Les compteurs de demandes et de comptes rendus portent sur la
     * discipline demandée (anatomie pathologique par défaut) ; patients,
     * clients et factures restent toutes disciplines confondues.</p>
     *
     * @param parDiscipline les mêmes compteurs pour chaque discipline, présent
     *                      seulement quand le module Biologie est actif — absent
     *                      du JSON sinon, pour que les clients d'anatomie
     *                      pathologique reçoivent exactement la réponse d'avant.
     *                      L'écran en tire la vue séparée ou cumulée selon le
     *                      réglage {@code bio_dashboard_mode}.
     */
    public record AdminStats(
        long valeurPatient, double crPatient,
        long valeurClient, double crClient,
        long valeurTestOrder, double crTestOrder,
        BigDecimal valeurInvoice, double crInvoice,
        long finishTest, long noFinishTest,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<Discipline, CompteursAdmin> parDiscipline
    ) {
        /** La réponse d'avant la biologie, sans répartition. */
        public AdminStats(long valeurPatient, double crPatient,
                          long valeurClient, double crClient,
                          long valeurTestOrder, double crTestOrder,
                          BigDecimal valeurInvoice, double crInvoice,
                          long finishTest, long noFinishTest) {
            this(valeurPatient, crPatient, valeurClient, crClient, valeurTestOrder, crTestOrder,
                    valeurInvoice, crInvoice, finishTest, noFinishTest, null);
        }
    }

    /** La part d'une discipline dans les cartes admin : demandes et comptes rendus. */
    public record CompteursAdmin(
        long valeurTestOrder, double crTestOrder,
        long finishTest, long noFinishTest
    ) {}

    /**
     * Stats secrétariat.
     *
     * @param parDiscipline comme pour {@link AdminStats} : présent seulement
     *                      quand le module Biologie est actif
     */
    public record SecretariatStats(
        long patients, long contrats, long tests,
        long testOrdersCount, long finishTest, long noFinishTest,
        long noSaveTest, long noFinishWeek,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<Discipline, CompteursSecretariat> parDiscipline
    ) {
        /** La réponse d'avant la biologie, sans répartition. */
        public SecretariatStats(long patients, long contrats, long tests,
                                long testOrdersCount, long finishTest, long noFinishTest,
                                long noSaveTest, long noFinishWeek) {
            this(patients, contrats, tests, testOrdersCount, finishTest, noFinishTest,
                    noSaveTest, noFinishWeek, null);
        }
    }

    /** La part d'une discipline dans les cartes du secrétariat. */
    public record CompteursSecretariat(
        long testOrdersCount, long finishTest, long noFinishTest,
        long noSaveTest, long noFinishWeek
    ) {}

    // Rapport disponible aujourd'hui
    public record ReportToday(
        String id, String testOrderId, String code,
        String patientLastname, String patientFirstname,
        String createdAt, int status, boolean isDeliver,
        String invoiceId
    ) {}

    // Stat par docteur
    public record DoctorStat(String id, String doctor, long assigne, long traite) {}

    // Top examen
    public record TopExamen(String testName, long totalDemandes) {}

    // Stats mensuelles
    public record MonthlyStats(
        long nombreTests, BigDecimal caTests, long totalPatientTest,
        List<ByItem> byHopital,
        List<ByItem> byMedecin,
        List<ByItem> byType
    ) {}

    public record ByItem(String nom, long totalPatients) {}

    // Utilisateurs connectés
    public record ConnectedUser(String id, String lastname, String firstname, String email) {}

    // Revenus finance
    public record RevenueData(
        BigDecimal totalCurrentWeek,
        BigDecimal totalLastWeek,
        BigDecimal totalToday,
        List<DayRevenue> currentWeekByDay,
        List<DayRevenue> lastWeekByDay
    ) {}

    public record DayRevenue(String date, BigDecimal total) {}

    // Statut factures (4 catégories)
    public record InvoiceStatus(
        long invoicePaid, long invoiceNoPaid,
        long refundPaid, long refundNoPaid,
        BigDecimal invoiceTotalPaid, BigDecimal invoiceTotalNoPaid,
        BigDecimal refundTotalPaid, BigDecimal refundTotalNoPaid
    ) {}

    /**
     * Statut examens (pour docteur).
     *
     * @param parDiscipline comme pour {@link AdminStats} : présent seulement
     *                      quand le module Biologie est actif
     */
    public record ExamStatusChart(
        long termine, long enAttente,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<Discipline, CompteursExamens> parDiscipline
    ) {
        /** La réponse d'avant la biologie, sans répartition. */
        public ExamStatusChart(long termine, long enAttente) {
            this(termine, enAttente, null);
        }
    }

    /** La part d'une discipline dans le graphique d'un médecin. */
    public record CompteursExamens(long termine, long enAttente) {}

    // Rendez-vous pour docteur
    public record AppointmentDto(
        String id, String patientName, String date,
        String priority, String status, String message
    ) {}

    // Bons affectés au pathologiste
    public record DoctorOrder(
        String id,
        String code,
        String createdAt,
        String patientFirstname,
        String patientLastname,
        int reportStatus
    ) {}

    /**
     * Rappels automatiques des patients : section du tableau de bord et bandeau.
     *
     * @param parIssue  dernier rappel de chaque compte rendu sur la période, par issue
     *                  (toutes les issues présentes, à 0 si aucune)
     * @param aRappeler patients non joints, ni marqués informés, ni venus chercher
     *                  leur résultat — toutes dates confondues : c'est le bandeau
     * @param reglagesManquants ce qui empêche des appels de partir (clés audio vides,
     *                  {@code fluidchat_api}) ; vide si tout est prêt — second bandeau
     */
    public record Rappels(Map<IssueRappel, Long> parIssue, long aRappeler, List<String> reglagesManquants) {}
}

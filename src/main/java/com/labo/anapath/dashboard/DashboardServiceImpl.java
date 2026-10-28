package com.labo.anapath.dashboard;

import com.labo.anapath.appointment.AppointmentRepository;
import com.labo.anapath.client.ClientRepository;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.module.ModulesProperties;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.AppelByReportRepository;
import com.labo.anapath.report.IssueRappel;
import com.labo.anapath.report.ReglagesDeRappel;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import com.labo.anapath.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    /*
     * Discipline des comptages de demandes et de comptes-rendus : celle que
     * demande l'appelant, l'anatomie pathologique à défaut. Mêler la biologie
     * aux chiffres existants les ferait bouger sans que rien ne l'annonce ; la
     * répartition par discipline vient donc à côté (« parDiscipline »), et
     * seulement quand le module Biologie est actif. Les montants (factures,
     * chiffre d'affaires) restent, eux, toutes disciplines confondues.
     */

    private final PatientRepository patientRepository;
    private final ClientRepository clientRepository;
    private final TestOrderRepository testOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final ContratRepository contratRepository;
    private final LabTestRepository labTestRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppelByReportRepository appelByReportRepository;
    private final ReglagesDeRappel reglagesDeRappel;
    /** Interrupteur du module Biologie : sans lui, aucune répartition par discipline. */
    private final ModulesProperties modules;

    // -----------------------------------------------------------------------
    // Signatures d'avant la biologie : l'anatomie pathologique
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.AdminStats getAdminStats(UUID branchId) {
        return getAdminStats(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public DashboardDto.SecretariatStats getSecretariatStats(UUID branchId) {
        return getSecretariatStats(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public List<DashboardDto.ReportToday> getReportsToday(UUID branchId) {
        return getReportsToday(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public List<DashboardDto.DoctorStat> getDoctorStats(UUID branchId) {
        return getDoctorStats(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public List<DashboardDto.TopExamen> getTopExamens(UUID branchId) {
        return getTopExamens(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public DashboardDto.MonthlyStats getMonthlyStats(UUID branchId) {
        return getMonthlyStats(branchId, Discipline.PATHOLOGY);
    }

    @Override
    public DashboardDto.ExamStatusChart getExamStatusForDoctor(UUID userId, UUID branchId) {
        return getExamStatusForDoctor(userId, branchId, Discipline.PATHOLOGY);
    }

    @Override
    public List<DashboardDto.DoctorOrder> getDoctorOrders(UUID userId, UUID branchId) {
        return getDoctorOrders(userId, branchId, Discipline.PATHOLOGY);
    }

    @Override
    public List<DashboardDto.DoctorOrder> getDoctorOrdersToday(UUID userId, UUID branchId) {
        return getDoctorOrdersToday(userId, branchId, Discipline.PATHOLOGY);
    }

    // -----------------------------------------------------------------------
    // Admin KPIs
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.AdminStats getAdminStats(UUID branchId, Discipline discipline) {
        Discipline laDiscipline = disciplineOuDefaut(discipline);
        LocalDate now = LocalDate.now();
        LocalDate startCurrentMonth = now.withDayOfMonth(1);
        LocalDate startPrevMonth = startCurrentMonth.minusMonths(1);
        LocalDate endPrevMonth = startCurrentMonth.minusDays(1);

        // Patients
        long valPatient = patientRepository.countByBranchId(branchId);
        long currPatient = patientRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startCurrentMonth.atStartOfDay(), now.atTime(LocalTime.MAX));
        long prevPatient = patientRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startPrevMonth.atStartOfDay(), endPrevMonth.atTime(LocalTime.MAX));
        double crPatient = calcGrowth(currPatient, prevPatient);

        // Clients
        long valClient = clientRepository.countByBranchId(branchId);
        long currClient = clientRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startCurrentMonth.atStartOfDay(), now.atTime(LocalTime.MAX));
        long prevClient = clientRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startPrevMonth.atStartOfDay(), endPrevMonth.atTime(LocalTime.MAX));
        double crClient = calcGrowth(currClient, prevClient);

        // TestOrders et donut « STATUT D'EXAMENS », pour la discipline demandée
        DashboardDto.CompteursAdmin compteurs = compteursAdmin(branchId, laDiscipline);

        // Invoices
        BigDecimal valInvoice = invoiceRepository.sumTotalByBranchId(branchId);
        if (valInvoice == null) valInvoice = BigDecimal.ZERO;
        BigDecimal currInv = invoiceRepository.sumTotalByBranchIdAndDateRange(branchId,
                startCurrentMonth, now);
        if (currInv == null) currInv = BigDecimal.ZERO;
        BigDecimal prevInv = invoiceRepository.sumTotalByBranchIdAndDateRange(branchId,
                startPrevMonth, endPrevMonth);
        if (prevInv == null) prevInv = BigDecimal.ZERO;
        double crInvoice = prevInv.compareTo(BigDecimal.ZERO) != 0
                ? currInv.subtract(prevInv).multiply(BigDecimal.valueOf(100))
                        .divide(prevInv, 2, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        return new DashboardDto.AdminStats(valPatient, crPatient, valClient, crClient,
                compteurs.valeurTestOrder(), compteurs.crTestOrder(), valInvoice, crInvoice,
                compteurs.finishTest(), compteurs.noFinishTest(),
                parDiscipline(laDiscipline, compteurs, d -> compteursAdmin(branchId, d)));
    }

    /** Demandes (total et croissance du mois) et donut de statut, pour une discipline. */
    private DashboardDto.CompteursAdmin compteursAdmin(UUID branchId, Discipline discipline) {
        LocalDate now = LocalDate.now();
        LocalDate startCurrentMonth = now.withDayOfMonth(1);
        LocalDate startPrevMonth = startCurrentMonth.minusMonths(1);
        LocalDate endPrevMonth = startCurrentMonth.minusDays(1);

        long valTO = testOrderRepository.countByBranchIdAndDiscipline(branchId, discipline);
        long currTO = testOrderRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startCurrentMonth.atStartOfDay(), now.atTime(LocalTime.MAX), discipline);
        long prevTO = testOrderRepository.countByBranchIdAndCreatedAtBetween(branchId,
                startPrevMonth.atStartOfDay(), endPrevMonth.atTime(LocalTime.MAX), discipline);
        double crTO = calcGrowth(currTO, prevTO);

        // Donut « STATUT D'EXAMENS » — équivalent du $totalByStatus de Laravel :
        // terminé = compte rendu validé ou remis, en attente = brouillon ou en relecture.
        long finishTest = reportRepository.countByBranchIdAndStatusIn(branchId,
                List.of(ReportStatus.VALIDATED, ReportStatus.DELIVERED), discipline);
        long noFinishTest = reportRepository.countByBranchIdAndStatusIn(branchId,
                List.of(ReportStatus.DRAFT, ReportStatus.PENDING_REVIEW), discipline);

        return new DashboardDto.CompteursAdmin(valTO, crTO, finishTest, noFinishTest);
    }

    // -----------------------------------------------------------------------
    // Secrétariat stats
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.SecretariatStats getSecretariatStats(UUID branchId, Discipline discipline) {
        Discipline laDiscipline = disciplineOuDefaut(discipline);
        long patients = patientRepository.countByBranchId(branchId);
        long contrats = contratRepository.countByBranchId(branchId);
        long tests = labTestRepository.countByBranchId(branchId);
        DashboardDto.CompteursSecretariat compteurs = compteursSecretariat(branchId, laDiscipline);

        return new DashboardDto.SecretariatStats(patients, contrats, tests,
                compteurs.testOrdersCount(), compteurs.finishTest(), compteurs.noFinishTest(),
                compteurs.noSaveTest(), compteurs.noFinishWeek(),
                parDiscipline(laDiscipline, compteurs, d -> compteursSecretariat(branchId, d)));
    }

    /** Les cinq indicateurs de charge du secrétariat, pour une discipline. */
    private DashboardDto.CompteursSecretariat compteursSecretariat(UUID branchId, Discipline discipline) {
        long toCount = testOrderRepository.countByBranchIdAndDiscipline(branchId, discipline);
        // Les quatre indicateurs de charge portent sur l'année en cours.
        //
        // Ils comptaient tout l'historique, et disaient donc surtout l'arriéré :
        // 331 dossiers sans compte rendu dont 237 antérieurs à cette année, 242
        // demandes en attente qui traînent depuis 2023. Un indicateur de charge
        // qui ne peut plus redescendre n'est plus lu, et le travail du jour s'y
        // perdait. Les totaux cumulés — patients, contrats, examens — restent
        // cumulés juste au-dessus : eux répondent à « combien depuis toujours ».
        LocalDateTime debutDAnnee =
                java.time.LocalDate.now().withDayOfYear(1).atStartOfDay();
        // finishTest = reports livrés, noFinishTest = reports non livrés
        long finishTest = reportRepository.countByBranchIdAndIsDelivered(branchId, true, debutDAnnee, discipline);
        long noFinishTest = reportRepository.countByBranchIdAndIsDelivered(branchId, false, debutDAnnee, discipline);
        // noSaveTest = test_orders sans report
        long noSaveTest = testOrderRepository.countByBranchIdAndReportIsNull(branchId, debutDAnnee, discipline.name());
        // noFinishWeek = testorders pending depuis > 3 semaines
        LocalDateTime threeWeeksAgo = LocalDateTime.now().minusWeeks(3);
        long noFinishWeek = testOrderRepository.countByBranchIdAndStatusPendingAndCreatedAtBefore(
                branchId, threeWeeksAgo, debutDAnnee, discipline);

        return new DashboardDto.CompteursSecretariat(toCount, finishTest, noFinishTest,
                noSaveTest, noFinishWeek);
    }

    // -----------------------------------------------------------------------
    // Reports today
    // -----------------------------------------------------------------------

    @Override
    public List<DashboardDto.ReportToday> getReportsToday(UUID branchId, Discipline discipline) {
        LocalDate today = LocalDate.now();
        return reportRepository.findReportsTodayByBranchId(branchId, today, disciplineOuDefaut(discipline).name()).stream()
                .map(p -> new DashboardDto.ReportToday(
                        p.getId(), p.getTestOrderId(), p.getCode(),
                        p.getPatientLastname(), p.getPatientFirstname(),
                        p.getCreatedAt(), p.getStatus() != null ? p.getStatus() : 0,
                        Boolean.TRUE.equals(p.getIsDeliver()), p.getInvoiceId()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Doctor stats
    // -----------------------------------------------------------------------

    @Override
    public List<DashboardDto.DoctorStat> getDoctorStats(UUID branchId, Discipline discipline) {
        return testOrderRepository.getDoctorStatsByBranchId(branchId, disciplineOuDefaut(discipline).name()).stream()
                .map(p -> new DashboardDto.DoctorStat(p.getId(), p.getDoctor(),
                        p.getAssigne() != null ? p.getAssigne() : 0L,
                        p.getTraite() != null ? p.getTraite() : 0L))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Top examens
    // -----------------------------------------------------------------------

    @Override
    public List<DashboardDto.TopExamen> getTopExamens(UUID branchId, Discipline discipline) {
        return testOrderRepository.getTopExamensByBranchId(branchId, 7, disciplineOuDefaut(discipline).name()).stream()
                .map(p -> new DashboardDto.TopExamen(p.getTestName(),
                        p.getTotalDemandes() != null ? p.getTotalDemandes() : 0L))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Monthly stats
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.MonthlyStats getMonthlyStats(UUID branchId, Discipline disciplineDemandee) {
        Discipline laDiscipline = disciplineOuDefaut(disciplineDemandee);
        // Mois seul, sans l'année : voir le commentaire de parité Laravel dans
        // TestOrderRepository (section « Dashboard — stats mensuelles »).
        int month = LocalDate.now().getMonthValue();

        String discipline = laDiscipline.name();
        long nombreTests = testOrderRepository.countByBranchIdAndMonth(branchId, month, discipline);
        // Chiffre d'affaires : toutes disciplines confondues, comme les factures.
        BigDecimal caTests = testOrderRepository.sumPriceByBranchIdAndMonth(branchId, month);
        if (caTests == null) caTests = BigDecimal.ZERO;
        long totalPatientTest = testOrderRepository.countPatientsByBranchIdAndMonth(branchId, month, discipline);

        List<DashboardDto.ByItem> byHopital = testOrderRepository.countByHospitalAndMonth(branchId, month, discipline)
                .stream().map(p -> new DashboardDto.ByItem(p.getNom(), p.getTotalPatients() != null ? p.getTotalPatients() : 0L)).toList();
        List<DashboardDto.ByItem> byMedecin = testOrderRepository.countByDoctorAndMonth(branchId, month, discipline)
                .stream().map(p -> new DashboardDto.ByItem(p.getNom(), p.getTotalPatients() != null ? p.getTotalPatients() : 0L)).toList();
        // Sans discipline : la jointure sur type_orders écarte déjà la biologie.
        // Un bon de biologie n'a pas de type de bon : la répartition par type
        // n'a pas de sens pour elle, et la requête rendrait celle de l'anapath.
        List<DashboardDto.ByItem> byType = laDiscipline != Discipline.PATHOLOGY
                ? List.of()
                : testOrderRepository.countByTypeOrderAndMonth(branchId, month)
                .stream().map(p -> new DashboardDto.ByItem(p.getNom(), p.getTotalPatients() != null ? p.getTotalPatients() : 0L)).toList();

        return new DashboardDto.MonthlyStats(nombreTests, caTests, totalPatientTest,
                byHopital, byMedecin, byType);
    }

    // -----------------------------------------------------------------------
    // Connected users
    // -----------------------------------------------------------------------

    @Override
    public List<DashboardDto.ConnectedUser> getConnectedUsers(UUID branchId) {
        return userRepository.findConnectedUsersByBranchId(branchId, LocalDate.now()).stream()
                .map(p -> new DashboardDto.ConnectedUser(p.getId(), p.getLastname(),
                        p.getFirstname(), p.getEmail()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Revenue data
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.RevenueData getRevenueData(UUID branchId) {
        LocalDate today = LocalDate.now();
        LocalDate startCurrentWeek = today.with(DayOfWeek.MONDAY);
        LocalDate endCurrentWeek = today.with(DayOfWeek.SUNDAY);
        LocalDate startLastWeek = startCurrentWeek.minusWeeks(1);
        LocalDate endLastWeek = endCurrentWeek.minusWeeks(1);

        BigDecimal totalCurrentWeek = invoiceRepository.sumPaidSalesByBranchIdAndDateRange(branchId, startCurrentWeek, endCurrentWeek);
        if (totalCurrentWeek == null) totalCurrentWeek = BigDecimal.ZERO;
        BigDecimal totalLastWeek = invoiceRepository.sumPaidSalesByBranchIdAndDateRange(branchId, startLastWeek, endLastWeek);
        if (totalLastWeek == null) totalLastWeek = BigDecimal.ZERO;
        BigDecimal totalToday = invoiceRepository.sumPaidByBranchIdAndDate(branchId, today);
        if (totalToday == null) totalToday = BigDecimal.ZERO;

        // La requête ne renvoie que les jours ayant au moins une facture encaissée :
        // on complète la semaine pour toujours livrer 7 points, du lundi au dimanche.
        // Sans cela, le graphe du chiffre d'affaires n'a aucun point à tracer les
        // semaines creuses, et les deux séries ne s'alignent pas jour par jour.
        List<DashboardDto.DayRevenue> currentWeekByDay =
                fillWeek(startCurrentWeek, invoiceRepository.sumPaidByDayInRange(branchId, startCurrentWeek, endCurrentWeek));
        List<DashboardDto.DayRevenue> lastWeekByDay =
                fillWeek(startLastWeek, invoiceRepository.sumPaidByDayInRange(branchId, startLastWeek, endLastWeek));

        return new DashboardDto.RevenueData(totalCurrentWeek, totalLastWeek, totalToday,
                currentWeekByDay, lastWeekByDay);
    }

    /**
     * Complète une série journalière sur les 7 jours de la semaine commençant à
     * {@code weekStart} : chaque jour sans facture encaissée reçoit un total de zéro.
     *
     * @param weekStart lundi de la semaine concernée
     * @param rows      lignes renvoyées par la base (uniquement les jours non vides)
     * @return 7 points ordonnés du lundi au dimanche
     */
    private List<DashboardDto.DayRevenue> fillWeek(
            LocalDate weekStart, List<DashboardProjection.DayRevenue> rows) {
        java.util.Map<String, BigDecimal> byDate = new java.util.HashMap<>();
        for (DashboardProjection.DayRevenue row : rows) {
            byDate.put(row.getDate(), nullSafe(row.getTotal()));
        }
        List<DashboardDto.DayRevenue> week = new java.util.ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            String date = weekStart.plusDays(i).toString();
            week.add(new DashboardDto.DayRevenue(date, byDate.getOrDefault(date, BigDecimal.ZERO)));
        }
        return week;
    }

    // -----------------------------------------------------------------------
    // Invoice status (4 catégories)
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.InvoiceStatus getInvoiceStatus(UUID branchId) {
        long invoicePaid = invoiceRepository.countByBranchIdAndStatusInvoiceAndPaid(branchId, 0, true);
        BigDecimal invoiceTotalPaid = invoiceRepository.sumByBranchIdAndStatusInvoiceAndPaid(branchId, 0, true);
        long invoiceNoPaid = invoiceRepository.countByBranchIdAndStatusInvoiceAndPaid(branchId, 0, false);
        BigDecimal invoiceTotalNoPaid = invoiceRepository.sumByBranchIdAndStatusInvoiceAndPaid(branchId, 0, false);
        long refundPaid = invoiceRepository.countByBranchIdAndStatusInvoiceAndPaid(branchId, 1, true);
        BigDecimal refundTotalPaid = invoiceRepository.sumByBranchIdAndStatusInvoiceAndPaid(branchId, 1, true);
        long refundNoPaid = invoiceRepository.countByBranchIdAndStatusInvoiceAndPaid(branchId, 1, false);
        BigDecimal refundTotalNoPaid = invoiceRepository.sumByBranchIdAndStatusInvoiceAndPaid(branchId, 1, false);

        return new DashboardDto.InvoiceStatus(
                invoicePaid, invoiceNoPaid, refundPaid, refundNoPaid,
                nullSafe(invoiceTotalPaid), nullSafe(invoiceTotalNoPaid),
                nullSafe(refundTotalPaid), nullSafe(refundTotalNoPaid));
    }

    // -----------------------------------------------------------------------
    // Doctor-specific
    // -----------------------------------------------------------------------

    @Override
    public DashboardDto.ExamStatusChart getExamStatusForDoctor(UUID userId, UUID branchId,
                                                               Discipline discipline) {
        Discipline laDiscipline = disciplineOuDefaut(discipline);
        DashboardDto.CompteursExamens compteurs = compteursExamens(userId, branchId, laDiscipline);
        return new DashboardDto.ExamStatusChart(compteurs.termine(), compteurs.enAttente(),
                parDiscipline(laDiscipline, compteurs, d -> compteursExamens(userId, branchId, d)));
    }

    /** Examens remis et en attente d'un médecin, pour une discipline. */
    private DashboardDto.CompteursExamens compteursExamens(UUID userId, UUID branchId,
                                                           Discipline discipline) {
        long termine = testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                userId, branchId, TestOrderStatus.DELIVERED, discipline);
        long enAttente = testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                userId, branchId, TestOrderStatus.PENDING, discipline)
                + testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                userId, branchId, TestOrderStatus.VALIDATED, discipline);
        return new DashboardDto.CompteursExamens(termine, enAttente);
    }

    @Override
    public List<DashboardDto.AppointmentDto> getAppointmentsForDoctor(UUID userId, UUID branchId) {
        return appointmentRepository.findPendingByDoctorInterne(userId).stream()
                .map(p -> new DashboardDto.AppointmentDto(p.getId(), p.getPatientName(),
                        p.getDate(), p.getPriority(), p.getStatus(), p.getMessage()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Doctor orders (affectés via attribuate_doctor_id)
    // -----------------------------------------------------------------------

    @Override
    public List<DashboardDto.DoctorOrder> getDoctorOrders(UUID userId, UUID branchId, Discipline discipline) {
        return testOrderRepository.findAllByAttribuateDoctorId(userId, branchId, disciplineOuDefaut(discipline).name()).stream()
                .map(p -> new DashboardDto.DoctorOrder(p.getId(), p.getCode(), p.getCreatedAt(),
                        p.getPatientFirstname(), p.getPatientLastname(),
                        p.getReportStatus() != null ? p.getReportStatus() : 0))
                .toList();
    }

    @Override
    public List<DashboardDto.DoctorOrder> getDoctorOrdersToday(UUID userId, UUID branchId, Discipline discipline) {
        return testOrderRepository.findTodayByAttribuateDoctorId(userId, branchId, LocalDate.now(),
                        disciplineOuDefaut(discipline).name()).stream()
                .map(p -> new DashboardDto.DoctorOrder(p.getId(), p.getCode(), p.getCreatedAt(),
                        p.getPatientFirstname(), p.getPatientLastname(),
                        p.getReportStatus() != null ? p.getReportStatus() : 0))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Les compteurs de chaque discipline, ou rien si le module Biologie est éteint.
     *
     * <p>Rien, et non une seule entrée d'anatomie pathologique : le champ reste
     * alors absent du JSON, et les clients d'avant la biologie — l'application
     * mobile en tête — reçoivent la réponse d'avant, octet pour octet. La
     * discipline déjà comptée pour les champs principaux n'est pas recomptée.</p>
     */
    private <T> java.util.Map<Discipline, T> parDiscipline(
            Discipline dejaComptee, T compteursDejaComptes,
            java.util.function.Function<Discipline, T> compter) {
        if (!modules.isBiology()) {
            return null;
        }
        java.util.Map<Discipline, T> parDiscipline = new java.util.EnumMap<>(Discipline.class);
        for (Discipline d : Discipline.values()) {
            parDiscipline.put(d, d == dejaComptee ? compteursDejaComptes : compter.apply(d));
        }
        return parDiscipline;
    }

    /** La discipline donnée, ou l'anatomie pathologique à défaut. */
    private static Discipline disciplineOuDefaut(Discipline discipline) {
        return discipline != null ? discipline : Discipline.PATHOLOGY;
    }

    private double calcGrowth(long curr, long prev) {
        if (prev == 0) return 0.0;
        return Math.round(((double) (curr - prev) / prev) * 10000.0) / 100.0;
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardDto.Rappels getRappels(UUID branchId, LocalDate debut, LocalDate fin) {
        Map<IssueRappel, Long> parIssue = new EnumMap<>(IssueRappel.class);
        for (IssueRappel issue : IssueRappel.values()) {
            parIssue.put(issue, 0L);
        }
        for (Object[] ligne : appelByReportRepository.compterParIssue(
                branchId, debut.atStartOfDay(), fin.plusDays(1).atStartOfDay())) {
            parIssue.put(IssueRappel.valueOf((String) ligne[0]), ((Number) ligne[1]).longValue());
        }
        return new DashboardDto.Rappels(parIssue, appelByReportRepository.compterARappeler(branchId),
                reglagesDeRappel.manquants());
    }
}

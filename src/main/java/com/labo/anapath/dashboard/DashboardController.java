package com.labo.anapath.dashboard;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.report.NotificationServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Tableau de bord.
 *
 * <p>Les points d'entrée qui comptent des demandes ou des comptes rendus
 * acceptent un paramètre {@code discipline}, anatomie pathologique par défaut :
 * sans lui, la réponse est celle d'avant la biologie, ce dont dépendent les
 * applications mobiles déjà installées. Quand le module Biologie est actif, les
 * cartes ({@code /stats}, {@code /secretariat-stats}, {@code /doctor/exam-status})
 * portent en plus {@code parDiscipline}, la même série de compteurs pour chaque
 * discipline : l'écran en tire la vue séparée ou cumulée selon le réglage
 * {@code bio_dashboard_mode}. Montants, factures, utilisateurs connectés et
 * rendez-vous restent sans discipline.</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
// Sur la classe : quatorze routes, et la prochaine ajoutée hérite du garde sans
// qu'on y pense. Les montants exigent un droit de plus, posé sur la méthode —
// en redisant le premier, car une annotation de méthode remplace celle de la
// classe au lieu de s'y ajouter.
@PreAuthorize("hasAuthority('view-dashboard')")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardDto.AdminStats>> getAdminStats(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getAdminStats(principal.getBranchId(), discipline)));
    }

    @GetMapping("/secretariat-stats")
    public ResponseEntity<ApiResponse<DashboardDto.SecretariatStats>> getSecretariatStats(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getSecretariatStats(principal.getBranchId(), discipline)));
    }

    @GetMapping("/reports-today")
    public ResponseEntity<ApiResponse<List<DashboardDto.ReportToday>>> getReportsToday(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getReportsToday(principal.getBranchId(), discipline)));
    }

    @GetMapping("/doctor-stats")
    public ResponseEntity<ApiResponse<List<DashboardDto.DoctorStat>>> getDoctorStats(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getDoctorStats(principal.getBranchId(), discipline)));
    }

    @GetMapping("/top-examens")
    public ResponseEntity<ApiResponse<List<DashboardDto.TopExamen>>> getTopExamens(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getTopExamens(principal.getBranchId(), discipline)));
    }

    @GetMapping("/monthly-stats")
    public ResponseEntity<ApiResponse<DashboardDto.MonthlyStats>> getMonthlyStats(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getMonthlyStats(principal.getBranchId(), discipline)));
    }

    @GetMapping("/connected-users")
    public ResponseEntity<ApiResponse<List<DashboardDto.ConnectedUser>>> getConnectedUsers(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getConnectedUsers(principal.getBranchId())));
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasAuthority('view-dashboard') and hasAuthority('view-dashboard-finance')")
    public ResponseEntity<ApiResponse<DashboardDto.RevenueData>> getRevenue(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getRevenueData(principal.getBranchId())));
    }

    @GetMapping("/invoice-status")
    @PreAuthorize("hasAuthority('view-dashboard') and hasAuthority('view-dashboard-finance')")
    public ResponseEntity<ApiResponse<DashboardDto.InvoiceStatus>> getInvoiceStatus(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getInvoiceStatus(principal.getBranchId())));
    }

    @GetMapping("/doctor/exam-status")
    public ResponseEntity<ApiResponse<DashboardDto.ExamStatusChart>> getDoctorExamStatus(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getExamStatusForDoctor(principal.getId(), principal.getBranchId(), discipline)));
    }

    @GetMapping("/doctor/appointments")
    public ResponseEntity<ApiResponse<List<DashboardDto.AppointmentDto>>> getDoctorAppointments(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getAppointmentsForDoctor(principal.getId(), principal.getBranchId())));
    }

    @GetMapping("/doctor/orders")
    public ResponseEntity<ApiResponse<List<DashboardDto.DoctorOrder>>> getDoctorOrders(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getDoctorOrders(principal.getId(), principal.getBranchId(), discipline)));
    }

    @GetMapping("/doctor/orders-today")
    public ResponseEntity<ApiResponse<List<DashboardDto.DoctorOrder>>> getDoctorOrdersToday(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getDoctorOrdersToday(principal.getId(), principal.getBranchId(), discipline)));
    }

    /**
     * Rappels automatiques des patients : compteurs par issue sur la période
     * (7 derniers jours par défaut) et nombre de patients à rappeler, pour la
     * section du tableau de bord et le bandeau affiché sur toutes les pages.
     */
    @GetMapping("/rappels")
    public ResponseEntity<ApiResponse<DashboardDto.Rappels>> getRappels(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateBegin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            @AuthenticationPrincipal UserPrincipal principal) {
        LocalDate fin = dateEnd != null ? dateEnd : LocalDate.now(NotificationServiceImpl.FUSEAU_LABORATOIRE);
        LocalDate debut = dateBegin != null ? dateBegin : fin.minusDays(6);
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getRappels(principal.getBranchId(), debut, fin)));
    }
}

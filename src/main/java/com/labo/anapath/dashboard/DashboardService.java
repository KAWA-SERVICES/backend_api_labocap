package com.labo.anapath.dashboard;

import com.labo.anapath.common.Discipline;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Chiffres du tableau de bord.
 *
 * <p>Les comptages de demandes et de comptes rendus portent sur une discipline,
 * l'anatomie pathologique à défaut : les signatures sans discipline sont celles
 * d'avant la biologie et rendent exactement ce qu'elles rendaient. Montants,
 * patients, clients, utilisateurs connectés et rendez-vous n'ont pas de
 * discipline.</p>
 *
 * <p>Les signatures sans discipline restent déclarées ici plutôt qu'en
 * méthodes par défaut : une méthode par défaut échappe au
 * {@code @Transactional} de la classe d'implémentation.</p>
 */
public interface DashboardService {

    DashboardDto.AdminStats getAdminStats(UUID branchId);

    DashboardDto.AdminStats getAdminStats(UUID branchId, Discipline discipline);

    DashboardDto.SecretariatStats getSecretariatStats(UUID branchId);

    DashboardDto.SecretariatStats getSecretariatStats(UUID branchId, Discipline discipline);

    List<DashboardDto.ReportToday> getReportsToday(UUID branchId);

    List<DashboardDto.ReportToday> getReportsToday(UUID branchId, Discipline discipline);

    List<DashboardDto.DoctorStat> getDoctorStats(UUID branchId);

    List<DashboardDto.DoctorStat> getDoctorStats(UUID branchId, Discipline discipline);

    List<DashboardDto.TopExamen> getTopExamens(UUID branchId);

    List<DashboardDto.TopExamen> getTopExamens(UUID branchId, Discipline discipline);

    DashboardDto.MonthlyStats getMonthlyStats(UUID branchId);

    DashboardDto.MonthlyStats getMonthlyStats(UUID branchId, Discipline discipline);

    List<DashboardDto.ConnectedUser> getConnectedUsers(UUID branchId);
    DashboardDto.RevenueData getRevenueData(UUID branchId);
    DashboardDto.InvoiceStatus getInvoiceStatus(UUID branchId);

    DashboardDto.ExamStatusChart getExamStatusForDoctor(UUID userId, UUID branchId);

    DashboardDto.ExamStatusChart getExamStatusForDoctor(UUID userId, UUID branchId, Discipline discipline);

    List<DashboardDto.AppointmentDto> getAppointmentsForDoctor(UUID userId, UUID branchId);

    List<DashboardDto.DoctorOrder> getDoctorOrders(UUID userId, UUID branchId);

    List<DashboardDto.DoctorOrder> getDoctorOrders(UUID userId, UUID branchId, Discipline discipline);

    List<DashboardDto.DoctorOrder> getDoctorOrdersToday(UUID userId, UUID branchId);

    List<DashboardDto.DoctorOrder> getDoctorOrdersToday(UUID userId, UUID branchId, Discipline discipline);

    /** Rappels des patients de l'agence, du {@code debut} inclus à la {@code fin} incluse. */
    DashboardDto.Rappels getRappels(UUID branchId, LocalDate debut, LocalDate fin);
}

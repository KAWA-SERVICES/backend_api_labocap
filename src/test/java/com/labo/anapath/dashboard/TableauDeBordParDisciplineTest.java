package com.labo.anapath.dashboard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.labo.anapath.appointment.AppointmentRepository;
import com.labo.anapath.client.ClientRepository;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.module.ModulesProperties;
import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le tableau de bord par discipline.
 *
 * <p>Deux promesses. Sans paramètre, la réponse est celle d'avant la
 * biologie, au champ près — l'application mobile en dépend. Et la répartition
 * {@code parDiscipline} n'apparaît que lorsque le module Biologie est actif :
 * un laboratoire d'anatomie pathologique seule ne voit rien bouger.</p>
 */
class TableauDeBordParDisciplineTest {

    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final TestOrderRepository testOrderRepository = mock(TestOrderRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContratRepository contratRepository = mock(ContratRepository.class);
    private final LabTestRepository labTestRepository = mock(LabTestRepository.class);
    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final ModulesProperties modules = new ModulesProperties();

    private final DashboardServiceImpl service = new DashboardServiceImpl(
            patientRepository, clientRepository, testOrderRepository, invoiceRepository,
            reportRepository, userRepository, contratRepository, labTestRepository,
            appointmentRepository, mock(com.labo.anapath.report.AppelByReportRepository.class),
            mock(com.labo.anapath.report.ReglagesDeRappel.class), modules);

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Un laboratoire figé : 120 demandes d'anapath (12 ce mois, 10 le mois
     * dernier), 30 de biologie (6 ce mois, 3 le mois dernier) ; les comptes
     * rendus de même.
     */
    @BeforeEach
    void laboratoire() {
        when(patientRepository.countByBranchId(BRANCH_ID)).thenReturn(500L);
        when(patientRepository.countByBranchIdAndCreatedAtBetween(eq(BRANCH_ID), any(), any()))
                .thenReturn(20L, 10L);
        when(clientRepository.countByBranchId(BRANCH_ID)).thenReturn(40L);
        when(clientRepository.countByBranchIdAndCreatedAtBetween(eq(BRANCH_ID), any(), any()))
                .thenReturn(4L, 5L);
        when(invoiceRepository.sumTotalByBranchId(BRANCH_ID)).thenReturn(new BigDecimal("1500000"));
        when(invoiceRepository.sumTotalByBranchIdAndDateRange(eq(BRANCH_ID), any(), any()))
                .thenReturn(new BigDecimal("300000"), new BigDecimal("200000"));
        when(contratRepository.countByBranchId(BRANCH_ID)).thenReturn(7L);
        when(labTestRepository.countByBranchId(BRANCH_ID)).thenReturn(90L);

        pourLaDiscipline(Discipline.PATHOLOGY, 120, 12, 10, 80, 15, 60, 25, 9, 4, 50, 11);
        pourLaDiscipline(Discipline.BIOLOGY, 30, 6, 3, 20, 5, 18, 7, 2, 1, 9, 3);
    }

    private void pourLaDiscipline(Discipline d, long total, long ceMois, long moisDernier,
                                  long termines, long enCours, long remis, long nonRemis,
                                  long sansCompteRendu, long enSouffrance,
                                  long medecinRemis, long medecinEnAttente) {
        when(testOrderRepository.countByBranchIdAndDiscipline(BRANCH_ID, d)).thenReturn(total);
        when(testOrderRepository.countByBranchIdAndCreatedAtBetween(eq(BRANCH_ID), any(), any(), eq(d)))
                .thenReturn(ceMois, moisDernier);
        when(reportRepository.countByBranchIdAndStatusIn(BRANCH_ID,
                List.of(ReportStatus.VALIDATED, ReportStatus.DELIVERED), d)).thenReturn(termines);
        when(reportRepository.countByBranchIdAndStatusIn(BRANCH_ID,
                List.of(ReportStatus.DRAFT, ReportStatus.PENDING_REVIEW), d)).thenReturn(enCours);
        when(reportRepository.countByBranchIdAndIsDelivered(eq(BRANCH_ID), eq(true), any(), eq(d))).thenReturn(remis);
        when(reportRepository.countByBranchIdAndIsDelivered(eq(BRANCH_ID), eq(false), any(), eq(d))).thenReturn(nonRemis);
        when(testOrderRepository.countByBranchIdAndReportIsNull(eq(BRANCH_ID), any(), eq(d.name())))
                .thenReturn(sansCompteRendu);
        when(testOrderRepository.countByBranchIdAndStatusPendingAndCreatedAtBefore(
                eq(BRANCH_ID), any(), any(), eq(d))).thenReturn(enSouffrance);
        when(testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                USER_ID, BRANCH_ID, TestOrderStatus.DELIVERED, d)).thenReturn(medecinRemis);
        when(testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                USER_ID, BRANCH_ID, TestOrderStatus.PENDING, d)).thenReturn(medecinEnAttente);
        when(testOrderRepository.countByAssignedToUserIdAndBranchIdAndStatus(
                USER_ID, BRANCH_ID, TestOrderStatus.VALIDATED, d)).thenReturn(1L);
    }

    /** Les clés de premier niveau d'une réponse sérialisée, dans l'ordre. */
    private List<String> cles(Object reponse) {
        JsonNode noeud = json.valueToTree(reponse);
        List<String> cles = new ArrayList<>();
        cles.addAll(noeud.propertyNames());
        return cles;
    }

    @Nested
    @DisplayName("Sans paramètre, module éteint : la réponse d'avant")
    class ReponseDAvant {

        @Test
        @DisplayName("cartes admin : mêmes valeurs, mêmes champs, aucun comptage de biologie")
        void cartesAdmin() {
            DashboardDto.AdminStats obtenu = service.getAdminStats(BRANCH_ID);

            // La réponse que rendait le code d'avant la biologie sur ce laboratoire.
            DashboardDto.AdminStats avant = new DashboardDto.AdminStats(
                    500L, 100.0, 40L, -20.0, 120L, 20.0,
                    new BigDecimal("1500000"), 50.0, 80L, 15L);
            assertThat(obtenu).isEqualTo(avant);
            assertThat(cles(obtenu)).containsExactly(
                    "valeurPatient", "crPatient", "valeurClient", "crClient",
                    "valeurTestOrder", "crTestOrder", "valeurInvoice", "crInvoice",
                    "finishTest", "noFinishTest");
            verify(testOrderRepository, never()).countByBranchIdAndDiscipline(any(), eq(Discipline.BIOLOGY));
            verify(reportRepository, never()).countByBranchIdAndStatusIn(any(), anyList(), eq(Discipline.BIOLOGY));
        }

        @Test
        @DisplayName("secrétariat : mêmes valeurs, mêmes champs")
        void secretariat() {
            DashboardDto.SecretariatStats obtenu = service.getSecretariatStats(BRANCH_ID);

            assertThat(obtenu).isEqualTo(new DashboardDto.SecretariatStats(500L, 7L, 90L, 120L, 60L, 25L, 9L, 4L));
            assertThat(cles(obtenu)).containsExactly("patients", "contrats", "tests",
                    "testOrdersCount", "finishTest", "noFinishTest", "noSaveTest", "noFinishWeek");
            verify(reportRepository, never()).countByBranchIdAndIsDelivered(any(), any(Boolean.class), any(),
                    eq(Discipline.BIOLOGY));
        }

        @Test
        @DisplayName("graphique du médecin : mêmes valeurs, mêmes champs")
        void graphiqueDuMedecin() {
            DashboardDto.ExamStatusChart obtenu = service.getExamStatusForDoctor(USER_ID, BRANCH_ID);

            assertThat(obtenu).isEqualTo(new DashboardDto.ExamStatusChart(50L, 12L));
            assertThat(cles(obtenu)).containsExactly("termine", "enAttente");
        }

        @Test
        @DisplayName("stats mensuelles : l'anapath garde sa répartition par type")
        void statsMensuelles() {
            service.getMonthlyStats(BRANCH_ID);

            verify(testOrderRepository).countByTypeOrderAndMonth(eq(BRANCH_ID), anyInt());
            verify(testOrderRepository).countByBranchIdAndMonth(eq(BRANCH_ID), anyInt(), eq("PATHOLOGY"));
        }
    }

    @Nested
    @DisplayName("Module Biologie actif")
    class ModuleActif {

        @BeforeEach
        void activer() {
            modules.setBiology(true);
        }

        @Test
        @DisplayName("cartes admin : champs d'avant inchangés, répartition par discipline en plus")
        void cartesAdmin() {
            DashboardDto.AdminStats obtenu = service.getAdminStats(BRANCH_ID);

            assertThat(obtenu.valeurTestOrder()).isEqualTo(120L);
            assertThat(obtenu.finishTest()).isEqualTo(80L);
            assertThat(obtenu.parDiscipline()).containsOnlyKeys(Discipline.PATHOLOGY, Discipline.BIOLOGY);
            assertThat(obtenu.parDiscipline().get(Discipline.PATHOLOGY))
                    .isEqualTo(new DashboardDto.CompteursAdmin(120L, 20.0, 80L, 15L));
            assertThat(obtenu.parDiscipline().get(Discipline.BIOLOGY))
                    .isEqualTo(new DashboardDto.CompteursAdmin(30L, 100.0, 20L, 5L));
            assertThat(cles(obtenu)).last().isEqualTo("parDiscipline");
            // Montants sans discipline : toujours la même somme globale.
            assertThat(obtenu.valeurInvoice()).isEqualByComparingTo("1500000");
            // La discipline des champs principaux n'est pas comptée deux fois.
            verify(testOrderRepository).countByBranchIdAndDiscipline(BRANCH_ID, Discipline.PATHOLOGY);
        }

        @Test
        @DisplayName("secrétariat et médecin : répartition par discipline")
        void secretariatEtMedecin() {
            DashboardDto.SecretariatStats secretariat = service.getSecretariatStats(BRANCH_ID);
            assertThat(secretariat.parDiscipline().get(Discipline.BIOLOGY))
                    .isEqualTo(new DashboardDto.CompteursSecretariat(30L, 18L, 7L, 2L, 1L));
            assertThat(secretariat.parDiscipline().get(Discipline.PATHOLOGY))
                    .isEqualTo(new DashboardDto.CompteursSecretariat(120L, 60L, 25L, 9L, 4L));

            DashboardDto.ExamStatusChart medecin = service.getExamStatusForDoctor(USER_ID, BRANCH_ID);
            assertThat(medecin.parDiscipline().get(Discipline.BIOLOGY))
                    .isEqualTo(new DashboardDto.CompteursExamens(9L, 4L));
        }

        @Test
        @DisplayName("discipline=BIOLOGY : les champs principaux portent sur la biologie")
        void champsPrincipauxEnBiologie() {
            DashboardDto.AdminStats obtenu = service.getAdminStats(BRANCH_ID, Discipline.BIOLOGY);

            assertThat(obtenu.valeurTestOrder()).isEqualTo(30L);
            assertThat(obtenu.crTestOrder()).isEqualTo(100.0);
            assertThat(obtenu.finishTest()).isEqualTo(20L);
            assertThat(obtenu.noFinishTest()).isEqualTo(5L);
            assertThat(obtenu.valeurPatient()).isEqualTo(500L);
        }

        @Test
        @DisplayName("stats mensuelles en biologie : pas de répartition par type de bon")
        void statsMensuellesEnBiologie() {
            DashboardDto.MonthlyStats obtenu = service.getMonthlyStats(BRANCH_ID, Discipline.BIOLOGY);

            assertThat(obtenu.byType()).isEmpty();
            verify(testOrderRepository, never()).countByTypeOrderAndMonth(any(), anyInt());
            verify(testOrderRepository).countByBranchIdAndMonth(eq(BRANCH_ID), anyInt(), eq("BIOLOGY"));
        }
    }

    @Nested
    @DisplayName("Points d'entrée")
    class PointsDEntree {

        private final DashboardService dashboardService = mock(DashboardService.class);
        private MockMvc mvc;

        @BeforeEach
        void monter() {
            mvc = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build();
            UserPrincipal principal = new UserPrincipal(USER_ID, "admin@labo.bj", "x", BRANCH_ID, true, List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        }

        @AfterEach
        void demonter() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("les comptages acceptent une discipline, PATHOLOGY par défaut ; les montants non")
        void defautsDeclares() {
            List<String> avecDiscipline = new ArrayList<>();
            for (Method m : DashboardController.class.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    if (p.getType() != Discipline.class) continue;
                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    assertThat(rp).as(m.getName()).isNotNull();
                    assertThat(rp.defaultValue()).as(m.getName()).isEqualTo("PATHOLOGY");
                    avecDiscipline.add(m.getName());
                }
            }
            assertThat(avecDiscipline).containsExactlyInAnyOrder(
                    "getAdminStats", "getSecretariatStats", "getReportsToday", "getDoctorStats",
                    "getTopExamens", "getMonthlyStats", "getDoctorExamStatus", "getDoctorOrders",
                    "getDoctorOrdersToday");
            assertThat(Arrays.stream(DashboardController.class.getDeclaredMethods()).map(Method::getName))
                    .contains("getRevenue", "getInvoiceStatus", "getConnectedUsers", "getDoctorAppointments");
        }

        @Test
        @DisplayName("GET /dashboard/stats sans discipline compte PATHOLOGY")
        void liaison() throws Exception {
            mvc.perform(get("/api/v1/dashboard/stats")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/dashboard/stats").param("discipline", "BIOLOGY")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/dashboard/doctor/exam-status")).andExpect(status().isOk());

            verify(dashboardService).getAdminStats(BRANCH_ID, Discipline.PATHOLOGY);
            verify(dashboardService).getAdminStats(BRANCH_ID, Discipline.BIOLOGY);
            verify(dashboardService).getExamStatusForDoctor(USER_ID, BRANCH_ID, Discipline.PATHOLOGY);
        }
    }
}

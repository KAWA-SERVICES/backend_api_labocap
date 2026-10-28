package com.labo.anapath.common;

import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.report.NotificationService;
import com.labo.anapath.report.PdfReportService;
import com.labo.anapath.report.ReportController;
import com.labo.anapath.report.ReportService;
import com.labo.anapath.testorder.FiltreFileDuMedecin;
import com.labo.anapath.testorder.TestOrderAssignmentController;
import com.labo.anapath.testorder.TestOrderAssignmentService;
import com.labo.anapath.testorder.TestOrderController;
import com.labo.anapath.testorder.TestOrderFilterDto;
import com.labo.anapath.testorder.TestOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les listes, files et compteurs d'anatomie pathologique répondent comme avant
 * quand on ne leur précise pas de discipline.
 *
 * <p>Aucun client existant — web ou mobile — n'envoie {@code discipline}. Le
 * défaut à {@link Discipline#PATHOLOGY} est donc ce qui garantit qu'ils voient
 * exactement ce qu'ils voyaient avant la biologie. Un défaut oublié, ou un
 * paramètre rendu obligatoire, casserait les applications déjà installées.</p>
 */
class DisciplineParDefautDesPointsDEntreeTest {

    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    /** Les méthodes d'un contrôleur qui acceptent une discipline, et leur défaut. */
    private static List<String> pointsDEntreeAvecDiscipline(Class<?> controleur) {
        return Arrays.stream(controleur.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getParameters()).anyMatch(p -> p.getType() == Discipline.class))
                .peek(DisciplineParDefautDesPointsDEntreeTest::exigerLeDefautPathologie)
                .map(Method::getName)
                .sorted()
                .toList();
    }

    private static void exigerLeDefautPathologie(Method m) {
        for (Parameter p : m.getParameters()) {
            if (p.getType() != Discipline.class) continue;
            RequestParam rp = p.getAnnotation(RequestParam.class);
            assertThat(rp).as("%s : discipline doit être un @RequestParam", m.getName()).isNotNull();
            assertThat(rp.defaultValue()).as("%s : défaut de discipline", m.getName())
                    .isEqualTo("PATHOLOGY");
        }
    }

    @Test
    @DisplayName("toutes les listes touchées ont PATHOLOGY pour défaut")
    void defautsDeclares() {
        assertThat(pointsDEntreeAvecDiscipline(TestOrderController.class))
                .containsExactly("findAll", "getMyspaceOrders", "getMyspaceStats");
        assertThat(pointsDEntreeAvecDiscipline(ReportController.class))
                .containsExactly("findAll", "getList", "getPerformanceStats", "getSuivi", "getSuiviList");
        assertThat(pointsDEntreeAvecDiscipline(TestOrderAssignmentController.class))
                .containsExactly("arriere", "findAll", "lotsDeMaFile", "mesDemandes", "pageDeMaFile", "resumeDeMaFile");
    }

    // -------------------------------------------------------------------------
    // Liaison HTTP réelle : sans paramètre → PATHOLOGY, avec → la valeur.
    // -------------------------------------------------------------------------

    private final TestOrderService testOrderService = mock(TestOrderService.class);
    private final ReportService reportService = mock(ReportService.class);
    private final TestOrderAssignmentService assignmentService = mock(TestOrderAssignmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void monter() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new TestOrderController(testOrderService, reportService,
                                mock(com.labo.anapath.discussion.DiscussionService.class)),
                        new ReportController(reportService, mock(NotificationService.class),
                                mock(PdfReportService.class), mock(com.labo.anapath.report.ReportRepository.class)),
                        new TestOrderAssignmentController(assignmentService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        UserPrincipal principal = new UserPrincipal(USER_ID, "medecin@labo.bj", "x", BRANCH_ID, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void demonter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /test-orders sans discipline filtre sur PATHOLOGY")
    void listeDesDemandes() throws Exception {
        mvc.perform(get("/api/v1/test-orders")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-orders").param("discipline", "BIOLOGY")).andExpect(status().isOk());

        ArgumentCaptor<TestOrderFilterDto> filtres = ArgumentCaptor.forClass(TestOrderFilterDto.class);
        verify(testOrderService, org.mockito.Mockito.times(2))
                .findAll(anyInt(), anyInt(), filtres.capture(), eq(BRANCH_ID));
        assertThat(filtres.getAllValues()).extracting(TestOrderFilterDto::getDiscipline)
                .containsExactly(Discipline.PATHOLOGY, Discipline.BIOLOGY);
    }

    @Test
    @DisplayName("GET /reports/suivi/list sans discipline filtre sur PATHOLOGY")
    void suiviDesComptesRendus() throws Exception {
        mvc.perform(get("/api/v1/reports/suivi/list")).andExpect(status().isOk());

        verify(reportService).getSuiviList(eq(BRANCH_ID), anyInt(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), eq(Discipline.PATHOLOGY), eq(null));
    }

    @Test
    @DisplayName("GET /test-order-assignments/mes-demandes/page sans discipline vise PATHOLOGY")
    void fileDuMedecin() throws Exception {
        mvc.perform(get("/api/v1/test-order-assignments/mes-demandes/page")).andExpect(status().isOk());

        ArgumentCaptor<FiltreFileDuMedecin> filtre = ArgumentCaptor.forClass(FiltreFileDuMedecin.class);
        verify(assignmentService).pageDeLaFile(eq(USER_ID), filtre.capture(), anyInt(), anyInt());
        assertThat(filtre.getValue().discipline()).isEqualTo(Discipline.PATHOLOGY);
    }

    @Test
    @DisplayName("GET /test-orders/myspace/stats sans discipline compte PATHOLOGY")
    void monEspace() throws Exception {
        mvc.perform(get("/api/v1/test-orders/myspace/stats")).andExpect(status().isOk());

        verify(testOrderService).getMyspaceStats(USER_ID, BRANCH_ID, Discipline.PATHOLOGY);
    }
}

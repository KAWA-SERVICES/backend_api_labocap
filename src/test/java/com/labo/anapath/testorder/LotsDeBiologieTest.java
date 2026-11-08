package com.labo.anapath.testorder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.labo.anapath.branch.BranchRepository;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.TestPathologyMacroRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les lots de biologie, de la liste au bordereau.
 *
 * <p>Un bon de biologie n'a pas de type de bon : la liste historique des lots
 * (cyto/histo) ne peut pas le voir. La liste prend donc une discipline,
 * anatomie pathologique par défaut — et dans ce cas, rien ne change, pas même
 * la requête.</p>
 */
class LotsDeBiologieTest {

    private static final UUID BRANCH_ID = UUID.randomUUID();

    private final TestOrderAssignmentRepository assignmentRepository = mock(TestOrderAssignmentRepository.class);
    private final TestOrderAssignmentDetailRepository detailRepository = mock(TestOrderAssignmentDetailRepository.class);
    private final TestOrderRepository testOrderRepository = mock(TestOrderRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final TestPathologyMacroRepository macroRepository = mock(TestPathologyMacroRepository.class);
    private final SampleLabelRepository labelRepository = mock(SampleLabelRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TestOrderAssignmentServiceImpl service = new TestOrderAssignmentServiceImpl(
            assignmentRepository, detailRepository, testOrderRepository, userRepository,
            branchRepository, macroRepository, objectMapper, labelRepository, reportRepository);

    private static User biologiste() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstname("Awa");
        u.setLastname("Biologiste");
        return u;
    }

    private static TestOrder bon(String code, Discipline discipline) {
        TestOrder o = new TestOrder();
        o.setId(UUID.randomUUID());
        o.setBranchId(BRANCH_ID);
        o.setCode(code);
        o.setStatus(TestOrderStatus.VALIDATED);
        o.setDiscipline(discipline);
        return o;
    }

    private static TestOrderAssignment lot(User pour) {
        TestOrderAssignment a = new TestOrderAssignment();
        a.setId(UUID.randomUUID());
        a.setBranchId(BRANCH_ID);
        a.setUser(pour);
        a.setCode("AF26-0042");
        a.setDate(LocalDate.now());
        a.setDetails(new ArrayList<>());
        return a;
    }

    private static TestOrderAssignmentDetail ligne(TestOrderAssignment lot, TestOrder bon) {
        TestOrderAssignmentDetail d = new TestOrderAssignmentDetail();
        d.setId(UUID.randomUUID());
        d.setBranchId(BRANCH_ID);
        d.setTestOrderAssignment(lot);
        d.setTestOrder(bon);
        d.setTestOrderCode(bon.getCode());
        lot.getDetails().add(d);
        return d;
    }

    @Nested
    @DisplayName("Liste des lots")
    class ListeDesLots {

        @Test
        @DisplayName("sans discipline : la requête cyto/histo d'avant, et elle seule")
        void pathologieParDefaut() {
            when(assignmentRepository.findHistoCyto(eq(BRANCH_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.findAll(0, 20, BRANCH_ID);
            service.findAll(0, 20, BRANCH_ID, Discipline.PATHOLOGY);
            service.findAll(0, 20, BRANCH_ID, null);

            verify(assignmentRepository, org.mockito.Mockito.times(3))
                    .findHistoCyto(eq(BRANCH_ID), any(Pageable.class));
            verify(assignmentRepository, never()).findByDiscipline(any(), any(), any());
        }

        @Test
        @DisplayName("en biologie : les lots dont les demandes relèvent de la biologie")
        void biologie() {
            TestOrderAssignment lot = lot(biologiste());
            ligne(lot, bon("EX26-0100", Discipline.BIOLOGY));
            when(assignmentRepository.findByDiscipline(eq(BRANCH_ID), eq(Discipline.BIOLOGY), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(lot)));

            PageResponse<AssignmentResponseDto> page = service.findAll(0, 20, BRANCH_ID, Discipline.BIOLOGY);

            assertThat(page.content()).extracting(AssignmentResponseDto::code).containsExactly("AF26-0042");
            assertThat(page.content().get(0).detailCodes()).containsExactly("EX26-0100");
            verify(assignmentRepository, never()).findHistoCyto(any(), any());
        }
    }

    @Nested
    @DisplayName("Composer un lot de biologie")
    class ComposerUnLot {

        @Test
        @DisplayName("un lot confié à un biologiste reçoit un bon de biologie, sans macroscopie")
        void lotDeBiologie() {
            User biologiste = biologiste();
            AssignmentRequestDto demande = new AssignmentRequestDto();
            demande.setUserId(biologiste.getId());
            when(userRepository.findById(biologiste.getId())).thenReturn(Optional.of(biologiste));
            when(assignmentRepository.save(any(TestOrderAssignment.class))).thenAnswer(i -> {
                TestOrderAssignment a = i.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

            AssignmentResponseDto cree = service.create(demande, BRANCH_ID);

            assertThat(cree.userId()).isEqualTo(biologiste.getId());
            assertThat(cree.code()).startsWith("AF");

            // Le lot créé, on y range un bon de biologie.
            TestOrderAssignment lot = lot(biologiste);
            TestOrder bon = bon("EX26-0101", Discipline.BIOLOGY);
            when(assignmentRepository.findByIdAndBranchId(eq(lot.getId()), any())).thenReturn(Optional.of(lot));
            when(testOrderRepository.findByIdAndBranchId(eq(bon.getId()), any())).thenReturn(Optional.of(bon));
            when(detailRepository.affectationsCourantes(bon.getId())).thenReturn(List.of());
            AssignmentDetailRequestDto ajout = new AssignmentDetailRequestDto();
            ajout.setTestOrderId(bon.getId());

            AssignmentDetailResponseDto ligne = service.addDetail(lot.getId(), ajout);

            assertThat(ligne.testOrderCode()).isEqualTo("EX26-0101");
            verify(detailRepository).save(any(TestOrderAssignmentDetail.class));
            verifyNoInteractions(macroRepository);
        }

        @Test
        @DisplayName("un bon d'anatomie pathologique est refusé dans un lot de biologie")
        void pasDeMelange() {
            TestOrderAssignment lot = lot(biologiste());
            ligne(lot, bon("EX26-0102", Discipline.BIOLOGY));
            TestOrder anapath = bon("EX26-0103", Discipline.PATHOLOGY);
            when(assignmentRepository.findByIdAndBranchId(eq(lot.getId()), any())).thenReturn(Optional.of(lot));
            when(testOrderRepository.findByIdAndBranchId(eq(anapath.getId()), any())).thenReturn(Optional.of(anapath));
            AssignmentDetailRequestDto ajout = new AssignmentDetailRequestDto();
            ajout.setTestOrderId(anapath.getId());

            assertThatThrownBy(() -> service.addDetail(lot.getId(), ajout))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ne mêle pas les disciplines");
            verify(detailRepository, never()).save(any());
            verifyNoInteractions(macroRepository);
        }
    }

    @Nested
    @DisplayName("Bordereau imprimé")
    class Bordereau {

        /** Toutes les clés d'un nœud JSON, à plat — « details[].note » pour les tableaux. */
        private Set<String> cles(JsonNode noeud, String prefixe, Set<String> dans) {
            if (noeud.isObject()) {
                for (String cle : noeud.propertyNames()) {
                    String chemin = prefixe.isEmpty() ? cle : prefixe + "." + cle;
                    dans.add(chemin);
                    cles(noeud.get(cle), chemin, dans);
                }
            } else if (noeud.isArray()) {
                for (JsonNode element : noeud) {
                    cles(element, prefixe + "[]", dans);
                }
            }
            return dans;
        }

        @Test
        @DisplayName("un lot de biologie se dit de biologie, sans rien d'anatomie pathologique")
        void bordereauDeBiologie() throws Exception {
            TestOrderAssignment lot = lot(biologiste());
            lot.setNote("Série du matin");
            ligne(lot, bon("EX26-0104", Discipline.BIOLOGY)).setNote("Tube violet");
            ligne(lot, bon("EX26-0105", Discipline.BIOLOGY));
            when(assignmentRepository.findByIdAndBranchId(eq(lot.getId()), any())).thenReturn(Optional.of(lot));

            AssignmentPrintDto bordereau = service.getPrintData(lot.getId());

            assertThat(bordereau.discipline()).isEqualTo(Discipline.BIOLOGY);
            assertThat(bordereau.details()).extracting(AssignmentDetailResponseDto::testOrderCode)
                    .containsExactly("EX26-0104", "EX26-0105");
            // Rien de la macroscopie ni du type de bon : le bordereau ne porte
            // que des champs communs aux deux disciplines.
            Set<String> champs = cles(objectMapper.valueToTree(bordereau), "", new TreeSet<>());
            assertThat(champs).noneMatch(c -> c.toLowerCase().contains("macro")
                    || c.toLowerCase().contains("typeorder")
                    || c.toLowerCase().contains("immuno"));
            verifyNoInteractions(macroRepository);
        }

        @Test
        @DisplayName("un lot d'anatomie pathologique garde son bordereau, discipline en plus")
        void bordereauDAnatomiePathologique() {
            TestOrderAssignment lot = lot(biologiste());
            ligne(lot, bon("EX26-0106", Discipline.PATHOLOGY));
            when(assignmentRepository.findByIdAndBranchId(eq(lot.getId()), any())).thenReturn(Optional.of(lot));

            AssignmentPrintDto bordereau = service.getPrintData(lot.getId());

            assertThat(bordereau.discipline()).isEqualTo(Discipline.PATHOLOGY);
            assertThat(bordereau.assignment().code()).isEqualTo("AF26-0042");
        }

        @Test
        @DisplayName("un lot vide n'a pas de discipline ; une ligne réaffectée ne l'emporte pas sur une vivante")
        void disciplineDuLot() {
            TestOrderAssignment vide = lot(biologiste());
            assertThat(TestOrderAssignmentServiceImpl.disciplineDuLot(vide)).isNull();

            TestOrderAssignment lot = lot(biologiste());
            ligne(lot, bon("EX26-0107", Discipline.PATHOLOGY)).setRemplaceeLe(LocalDateTime.now());
            ligne(lot, bon("EX26-0108", Discipline.BIOLOGY));
            assertThat(TestOrderAssignmentServiceImpl.disciplineDuLot(lot)).isEqualTo(Discipline.BIOLOGY);
        }
    }

    @Nested
    @DisplayName("Point d'entrée GET /test-order-assignments")
    class PointDEntree {

        private final TestOrderAssignmentService assignmentService = mock(TestOrderAssignmentService.class);
        private MockMvc mvc;

        @BeforeEach
        void monter() {
            mvc = MockMvcBuilders.standaloneSetup(new TestOrderAssignmentController(assignmentService))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build();
            UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "secretariat@labo.bj", "x",
                    BRANCH_ID, true, List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        }

        @AfterEach
        void demonter() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("sans discipline : PATHOLOGY ; avec : la valeur demandée")
        void defaut() throws Exception {
            mvc.perform(get("/api/v1/test-order-assignments")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/test-order-assignments").param("discipline", "BIOLOGY"))
                    .andExpect(status().isOk());

            verify(assignmentService).findAll(anyInt(), anyInt(), eq(BRANCH_ID), eq(Discipline.PATHOLOGY));
            verify(assignmentService).findAll(anyInt(), anyInt(), eq(BRANCH_ID), eq(Discipline.BIOLOGY));
        }
    }
}

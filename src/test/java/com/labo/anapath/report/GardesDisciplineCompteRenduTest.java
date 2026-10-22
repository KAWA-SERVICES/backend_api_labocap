package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.hr.EmployeeRepository;
import com.labo.anapath.setting.SettingReportTemplateRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignmentDetailRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Les gestes propres à l'anatomie pathologique refusent un compte-rendu ou un
 * bon de biologie, au lieu de le traiter comme un compte-rendu rédigé.
 *
 * <p>Rien ici ne filtre : on refuse. Un compte-rendu de biologie ouvert par
 * erreur dans l'éditeur d'anatomie pathologique y verrait ses résultats écrasés
 * par du texte libre ; mieux vaut une erreur franche.</p>
 */
class GardesDisciplineCompteRenduTest {

    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static Report compteRendu(Discipline discipline) {
        Report r = new Report();
        ReflectionTestUtils.setField(r, "id", REPORT_ID);
        r.setBranchId(BRANCH_ID);
        r.setStatus(ReportStatus.DRAFT);
        r.setTags(new ArrayList<>());
        r.setDiscipline(discipline);
        return r;
    }

    private static TestOrder bon(Discipline discipline) {
        TestOrder o = new TestOrder();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setBranchId(BRANCH_ID);
        o.setCode("26-0001");
        o.setStatus(TestOrderStatus.VALIDATED);
        o.setDiscipline(discipline);
        return o;
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("ReportServiceImpl")
    class Service {

        @Mock private ReportRepository reportRepository;
        @Mock private LogReportRepository logReportRepository;
        @Mock private TestOrderRepository testOrderRepository;
        @Mock private UserRepository userRepository;
        @Mock private SettingReportTemplateRepository templateRepository;
        @Mock private TestOrderAssignmentDetailRepository assignmentDetailRepository;
        @Mock private ReportMapper reportMapper;

        @InjectMocks
        private ReportServiceImpl service;

        private void biologieEnBase() {
            when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any()))
                    .thenReturn(Optional.of(compteRendu(Discipline.BIOLOGY)));
        }

        @Test
        @DisplayName("createOrUpdate ne modifie plus : un reportId est refusé avant toute lecture")
        void createOrUpdateRefuseUnReportId() {
            ReportRequestDto dto = new ReportRequestDto();
            dto.setReportId(REPORT_ID);
            dto.setContent("texte libre");

            assertThatThrownBy(() -> service.createOrUpdate(dto, BRANCH_ID))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("PUT /reports/{id}");
            verify(reportRepository, never()).findById(any());
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("createOrUpdate refuse d'ouvrir un compte-rendu rédigé sur un bon de biologie")
        void createOrUpdateSurBonDeBiologie() {
            TestOrder bio = bon(Discipline.BIOLOGY);
            when(testOrderRepository.findByIdAndBranchId(eq(bio.getId()), any())).thenReturn(Optional.of(bio));
            ReportRequestDto dto = new ReportRequestDto();
            dto.setTestOrderId(bio.getId());

            assertThatThrownBy(() -> service.createOrUpdate(dto, BRANCH_ID))
                    .isInstanceOf(InvalidOperationException.class);
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("create refuse un bon de biologie")
        void createRefuse() {
            TestOrder bio = bon(Discipline.BIOLOGY);
            when(testOrderRepository.findByIdAndBranchId(eq(bio.getId()), any())).thenReturn(Optional.of(bio));
            ReportRequestDto dto = new ReportRequestDto();
            dto.setTestOrderId(bio.getId());

            assertThatThrownBy(() -> service.create(dto, BRANCH_ID))
                    .isInstanceOf(InvalidOperationException.class);
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("update refuse un compte-rendu de biologie")
        void updateRefuse() {
            biologieEnBase();

            assertThatThrownBy(() -> service.update(REPORT_ID, new ReportRequestDto(), USER_ID, BRANCH_ID))
                    .isInstanceOf(InvalidOperationException.class);
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("validate (point d'entrée d'anatomie pathologique) refuse la biologie")
        void validateRefuse() {
            biologieEnBase();

            assertThatThrownBy(() -> service.validate(REPORT_ID, USER_ID, null))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("biologie");
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("modèle d'impression : ni lecture ni association sur la biologie")
        void templateRefuse() {
            biologieEnBase();

            assertThatThrownBy(() -> service.getTemplate(REPORT_ID))
                    .isInstanceOf(InvalidOperationException.class);
            assertThatThrownBy(() -> service.setTemplate(REPORT_ID, UUID.randomUUID()))
                    .isInstanceOf(InvalidOperationException.class);
            verifyNoInteractions(templateRepository);
        }

        @Test
        @DisplayName("modifications après signature : refusées sur la biologie")
        void modificationsApresSignatureRefuse() {
            biologieEnBase();

            assertThatThrownBy(() -> service.getModificationsApresSignature(REPORT_ID))
                    .isInstanceOf(InvalidOperationException.class);
            verifyNoInteractions(logReportRepository);
        }

        @Test
        @DisplayName("modifications après signature : un compte-rendu inconnu rend toujours une liste vide")
        void modificationsApresSignatureInconnu() {
            when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.empty());

            assertThat(service.getModificationsApresSignature(REPORT_ID)).isEmpty();
        }

        @Test
        @DisplayName("le détail reste partagé et dit sa discipline")
        void detailPartage() {
            biologieEnBase();

            ReportDetailDto detail = service.findDetailById(REPORT_ID, BRANCH_ID);

            assertThat(detail.discipline()).isEqualTo(Discipline.BIOLOGY);
        }
    }

    @Test
    @DisplayName("l'empreinte après signature ne vaut que pour l'anatomie pathologique")
    void empreinteAnatomiePathologiqueSeulement() {
        User signataire = new User();
        ReflectionTestUtils.setField(signataire, "id", USER_ID);

        Report patho = compteRendu(Discipline.PATHOLOGY);
        patho.setSignatureDate(LocalDateTime.now());
        patho.setSignatory1(signataire);
        Report bio = compteRendu(Discipline.BIOLOGY);
        bio.setSignatureDate(LocalDateTime.now());
        bio.setSignatory1(signataire);

        assertThat(EmpreinteCompteRendu.concerne(patho)).isTrue();
        assertThat(EmpreinteCompteRendu.concerne(bio)).isFalse();
        assertThat(EmpreinteCompteRendu.concerne(compteRendu(Discipline.PATHOLOGY)))
                .as("non signé").isFalse();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("Macroscopie (TestPathologyMacroController)")
    class Macroscopie {

        @Mock private TestPathologyMacroRepository macroRepository;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private TestOrderRepository testOrderRepository;

        private final UserPrincipal principal = new UserPrincipal(
                USER_ID, "tech@labo.bj", "x", BRANCH_ID, true, List.of());

        private TestPathologyMacroController controleur() {
            return new TestPathologyMacroController(macroRepository, employeeRepository, testOrderRepository);
        }

        @Test
        @DisplayName("la file d'attente n'affiche jamais un bon de biologie")
        @SuppressWarnings("unchecked")
        void fileSansBiologie() {
            TestOrder patho = bon(Discipline.PATHOLOGY);
            TestOrder bio = bon(Discipline.BIOLOGY);
            when(macroRepository.findAll(any(Specification.class))).thenReturn(List.of());
            // Même si la requête laissait passer un bon de biologie, la seconde
            // garde l'écarte.
            when(testOrderRepository.findAll(any(Specification.class))).thenReturn(List.of(patho, bio));

            ResponseEntity<ApiResponse<List<TestPathologyMacroController.PendingMacroDto>>> reponse =
                    controleur().getPending(principal);

            assertThat(reponse.getBody().data())
                    .extracting(TestPathologyMacroController.PendingMacroDto::id)
                    .containsExactly(patho.getId());
        }

        @Test
        @DisplayName("assigner un laborantin à un bon de biologie est refusé")
        void assignationRefusee() {
            TestOrder bio = bon(Discipline.BIOLOGY);
            when(testOrderRepository.findByIdAndBranchId(bio.getId(), BRANCH_ID)).thenReturn(Optional.of(bio));

            assertThatThrownBy(() -> controleur().assign(
                    new TestPathologyMacroController.AssignMacroRequest(bio.getId(), UUID.randomUUID(), null),
                    principal))
                    .isInstanceOf(InvalidOperationException.class);
            verify(macroRepository, never()).save(any());
        }
    }
}

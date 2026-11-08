package com.labo.anapath.testorder;

import com.labo.anapath.branch.BranchRepository;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.report.TestPathologyMacro;
import com.labo.anapath.report.TestPathologyMacroRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestOrderAssignmentServiceImplTest {

    @Mock private TestOrderAssignmentRepository assignmentRepository;
    @Mock private TestOrderAssignmentDetailRepository detailRepository;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private TestPathologyMacroRepository macroRepository;

    @InjectMocks
    private TestOrderAssignmentServiceImpl service;

    private final UUID BRANCH_ID = UUID.randomUUID();
    private final UUID USER_ID = UUID.randomUUID();
    private final UUID ORDER_ID = UUID.randomUUID();
    private final UUID ASSIGNMENT_ID = UUID.randomUUID();

    private User buildUser() {
        User u = new User();
        u.setFirstname("Jean");
        u.setLastname("Dupont");
        return u;
    }

    private TestOrder buildOrder() {
        TestOrder o = new TestOrder();
        o.setBranchId(BRANCH_ID);
        o.setCode("EX26-0001");
        o.setStatus(TestOrderStatus.VALIDATED);
        return o;
    }

    private TestOrderAssignment buildAssignment() {
        TestOrderAssignment a = new TestOrderAssignment();
        a.setBranchId(BRANCH_ID);
        a.setUser(buildUser());
        a.setCode("AF26-0001");
        a.setDate(LocalDate.now());
        a.setDetails(new ArrayList<>());
        a.setId(ASSIGNMENT_ID);
        return a;
    }

    @Test
    @DisplayName("create - génère un code AF de l'année et persiste")
    void createAssignment_generatesUniqueCode() {
        AssignmentRequestDto dto = new AssignmentRequestDto();
        dto.setUserId(USER_ID);
        dto.setDate(LocalDate.now());

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(assignmentRepository.findMaxSequenceForYear(eq(BRANCH_ID), any()))
                .thenReturn(3);
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            TestOrderAssignment a = inv.getArgument(0);
            a.setDetails(new ArrayList<>());
            return a;
        });

        AssignmentResponseDto result = service.create(dto, BRANCH_ID);

        ArgumentCaptor<TestOrderAssignment> captor = ArgumentCaptor.forClass(TestOrderAssignment.class);
        verify(assignmentRepository).save(captor.capture());

        // AF, comme les 250 affectations du laboratoire — et la séquence
        // reprend après la dernière de l'année, pas après le total historique.
        String annee = String.valueOf(java.time.LocalDate.now().getYear() % 100);
        assertThat(captor.getValue().getCode()).isEqualTo("AF" + annee + "-0004");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("create - utilisateur inexistant → ResourceNotFoundException")
    void create_userNotFound_throws() {
        AssignmentRequestDto dto = new AssignmentRequestDto();
        dto.setUserId(USER_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(dto, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("addDetail - bon pas encore affecté → crée détail ET macro avec toutes étapes true")
    void addDetail_notYetAssigned_createsMacroWithAllStepsTrue() {
        TestOrder order = buildOrder();
        TestOrderAssignment assignment = buildAssignment();

        AssignmentDetailRequestDto dto = new AssignmentDetailRequestDto();
        dto.setTestOrderId(ORDER_ID);
        dto.setDate(LocalDate.now());

        when(assignmentRepository.findByIdAndBranchId(eq(ASSIGNMENT_ID), any())).thenReturn(Optional.of(assignment));
        when(testOrderRepository.findByIdAndBranchId(eq(ORDER_ID), any())).thenReturn(Optional.of(order));
        when(detailRepository.affectationsCourantes(ORDER_ID)).thenReturn(List.of());
        when(detailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(macroRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(macroRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addDetail(ASSIGNMENT_ID, dto);

        ArgumentCaptor<TestPathologyMacro> macroCaptor = ArgumentCaptor.forClass(TestPathologyMacro.class);
        verify(macroRepository).save(macroCaptor.capture());

        TestPathologyMacro saved = macroCaptor.getValue();
        assertThat(saved.getCirculation()).isTrue();
        assertThat(saved.getEmbedding()).isTrue();
        assertThat(saved.getMicrotomySpreading()).isTrue();
        assertThat(saved.getStaining()).isTrue();
        assertThat(saved.getMounting()).isTrue();
    }

    @Test
    @DisplayName("addDetail - déjà dans ce lot → met à jour la macro sans recréer de détail")
    void addDetail_alreadyAssigned_updatesMacroOnly() {
        TestOrder order = buildOrder();
        TestOrderAssignment assignment = buildAssignment();
        TestPathologyMacro existingMacro = new TestPathologyMacro();
        existingMacro.setAllStepsTrue();
        TestOrderAssignmentDetail existingDetail = new TestOrderAssignmentDetail();
        existingDetail.setTestOrder(order);
        existingDetail.setTestOrderAssignment(assignment);

        AssignmentDetailRequestDto dto = new AssignmentDetailRequestDto();
        dto.setTestOrderId(ORDER_ID);

        when(assignmentRepository.findByIdAndBranchId(eq(ASSIGNMENT_ID), any())).thenReturn(Optional.of(assignment));
        when(testOrderRepository.findByIdAndBranchId(eq(ORDER_ID), any())).thenReturn(Optional.of(order));
        when(detailRepository.affectationsCourantes(ORDER_ID)).thenReturn(List.of(existingDetail));
        when(macroRepository.findByTestOrderId(any())).thenReturn(Optional.of(existingMacro));
        when(macroRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addDetail(ASSIGNMENT_ID, dto);

        verify(macroRepository).save(any());
        // Rien à réécrire : la demande est déjà dans ce lot, et aucune étiquette
        // n'accompagne la reprise.
        org.mockito.Mockito.verify(detailRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("findAllImmuno - appelle la bonne méthode du repository")
    void findAllImmuno_callsCorrectRepo() {
        when(assignmentRepository.findImmuno(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.findAllImmuno(0, 20, BRANCH_ID);

        verify(assignmentRepository).findImmuno(any(), any());
    }

    @Test
    @DisplayName("create - saute un numéro déjà attribué plutôt que de le rejouer")
    void create_sauteUnCodeDejaPris() {
        AssignmentRequestDto dto = new AssignmentRequestDto();
        dto.setUserId(USER_ID);

        String annee = String.valueOf(LocalDate.now().getYear() % 100);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(assignmentRepository.findMaxSequenceForYear(eq(BRANCH_ID), any()))
                .thenReturn(7);
        // Le 0008 a existé puis été supprimé : son numéro figure sur un
        // bordereau, le réattribuer créerait deux affectations homonymes.
        when(assignmentRepository.existsByCodeIncludingDeleted("AF" + annee + "-0008"))
                .thenReturn(true);
        when(assignmentRepository.existsByCodeIncludingDeleted("AF" + annee + "-0009"))
                .thenReturn(false);
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            TestOrderAssignment a = inv.getArgument(0);
            a.setDetails(new ArrayList<>());
            return a;
        });

        service.create(dto, BRANCH_ID);

        ArgumentCaptor<TestOrderAssignment> captor =
                ArgumentCaptor.forClass(TestOrderAssignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("AF" + annee + "-0009");
    }
}

package com.labo.anapath.testorder;

import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.exception.DuplicateResourceException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.contract.Contrat;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.doctor.DoctorRepository;
import com.labo.anapath.doctor.HospitalRepository;
import com.labo.anapath.finance.Invoice;
import com.labo.anapath.finance.InvoiceDetailRepository;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.LogReportRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.setting.SettingRepository;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.test.TypeOrderRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestOrderValidationServiceTest {

    @Mock private TestOrderRepository testOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private ContratRepository contratRepository;
    @Mock private TypeOrderRepository typeOrderRepository;
    @Mock private LabTestRepository labTestRepository;
    @Mock private TestOrderMapper testOrderMapper;
    @Mock private ReportRepository reportRepository;
    @Mock private LogReportRepository logReportRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceDetailRepository invoiceDetailRepository;
    @Mock private UserRepository userRepository;
    @Mock private SettingRepository settingRepository;
    @Mock private com.labo.anapath.setting.SettingAppRepository settingAppRepository;

    @Mock private com.labo.anapath.biology.results.BiologyResultsLifecycle biologyResultsLifecycle;
    @InjectMocks
    private TestOrderServiceImpl testOrderService;

    private static final UUID ORDER_ID   = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID BRANCH_ID  = UUID.randomUUID();

    private TestOrder order;
    private Contrat contrat;
    private Patient patient;
    private User user;
    private TestOrderResponseDto responseDto;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setFirstname("Jean");
        patient.setLastname("Dupont");

        contrat = new Contrat();
        contrat.setInvoiceUnique(false); // individuel par défaut

        order = new TestOrder();
        order.setBranchId(BRANCH_ID);
        order.setStatus(TestOrderStatus.PENDING);
        order.setCode(null);
        order.setPatient(patient);
        order.setContrat(contrat);
        order.setSubtotal(100.0);
        order.setDiscount(0.0);
        order.setTotal(100.0);

        user = new User();

        responseDto = new TestOrderResponseDto(
                ORDER_ID, "EX26-0001", TestOrderStatus.VALIDATED, null,
                null, false, 100.0, 0.0, 100.0,
                UUID.randomUUID(), "Jean", "Dupont",
                null, null, null, null,
                null, null, null, null,
                null, null, Collections.emptyList(), BRANCH_ID, null,
                // reportId, reportStatus, reportIsDelivered, invoiceId, archive,
                // testAffiliate, option, les trois champs de facturation à un
                // tiers, puis assignedUserName et discipline.
                null, null, false, null, null, null, null,
                null, null, null,
                null, null);
    }

    // --- AC3 ---

    @Test
    @DisplayName("updateStatus - statut invalide → BusinessException")
    void updateStatus_invalid_status_throws() {
        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "DELIVERED", USER_ID, BRANCH_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VALIDATED");

        verify(testOrderRepository, never()).findByIdAndBranchId(any(), any());
    }

    // --- AC4 ---

    @Test
    @DisplayName("updateStatus - bon inexistant → ResourceNotFoundException")
    void updateStatus_order_not_found_throws() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- AC10 ---

    @Test
    @DisplayName("updateStatus - pas de contrat → BusinessException TEST_ORDER_NO_CONTRACT")
    void updateStatus_no_contract_throws() {
        order.setContrat(null);
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TEST_ORDER_NO_CONTRACT");
    }

    // --- AC9 ---

    @Test
    @DisplayName("updateStatus - race condition sur le code unique → DuplicateResourceException CODE_GENERATION_CONFLICT")
    void updateStatus_race_condition_throws_CODE_GENERATION_CONFLICT() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        // Aucun préfixe configuré dans setting_apps → code « aa-0001 » (format Laravel)
        when(settingAppRepository.findByKeyAndBranchId(anyString(), eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(testOrderRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("CODE_GENERATION_CONFLICT");
    }

    // --- AC1 + AC5 + AC6 + AC7 ---

    @Test
    @DisplayName("updateStatus - génère code et passe à VALIDATED (contrat individuel)")
    void updateStatus_generates_code_sets_validated() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(testOrderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(eq(BRANCH_ID), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        TestOrderResponseDto result = testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<TestOrder> captor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository).saveAndFlush(captor.capture());
        // Sans préfixe configuré dans setting_apps, le code est « aa-0001 » (format Laravel).
        assertThat(captor.getValue().getCode()).isNotNull().matches("\\d{2}-\\d{4}");
        assertThat(captor.getValue().getStatus()).isEqualTo(TestOrderStatus.VALIDATED);
        assertThat(result).isNotNull();
    }

    // --- AC2 ---

    @Test
    @DisplayName("updateStatus - idempotent si code déjà non null")
    void updateStatus_idempotent_already_validated() {
        order.setCode("EX26-0001");
        order.setStatus(TestOrderStatus.VALIDATED);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        verify(testOrderRepository, never()).saveAndFlush(any());
    }

    // --- AC5 ---

    @Test
    @DisplayName("updateStatus - crée un Report si aucun n'existe")
    void updateStatus_creates_report_when_none_exists() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(testOrderRepository.saveAndFlush(any())).thenAnswer(inv -> {
            order.setCode("EX26-0001");
            return order;
        });
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getCode()).startsWith("CO");
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    @Test
    @DisplayName("updateStatus - le Report créé hérite de la discipline du bon")
    void updateStatus_report_herite_discipline_du_bon() {
        // Aucune donnée de biologie n'existe encore, mais la colonne n'est plus
        // modifiable après l'insertion : si le compte-rendu naît en PATHOLOGY
        // sur un bon de biologie, il y reste pour toujours.
        order.setDiscipline(com.labo.anapath.common.Discipline.BIOLOGY);
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        // Pas de lecture du texte par défaut : il n'a pas de sens en biologie
        // (voir BonsDeBiologieTest).
        when(testOrderRepository.saveAndFlush(any())).thenAnswer(inv -> {
            order.setCode("EX26-0001");
            return order;
        });
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getDiscipline())
                .isEqualTo(com.labo.anapath.common.Discipline.BIOLOGY);
    }

    // --- AC5 (mise à jour) ---

    @Test
    @DisplayName("updateStatus - met à jour le code du Report existant")
    void updateStatus_updates_report_when_exists() {
        order.setCode("EX26-0001");
        order.setStatus(TestOrderStatus.VALIDATED);

        Report existingReport = new Report();
        existingReport.setCode("COOLD-CODE");
        existingReport.setTestOrder(order);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.of(existingReport));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getCode()).isEqualTo("COEX26-0001");
    }

    // --- AC7 (contrat individuel) ---

    @Test
    @DisplayName("updateStatus - contrat individuel → crée une Invoice")
    void updateStatus_individual_contract_creates_invoice() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(testOrderRepository.saveAndFlush(any())).thenAnswer(inv -> {
            order.setCode("EX26-0001");
            return order;
        });
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByBranchIdAndCodeNotNullAndYear(any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());
        when(testOrderMapper.toResponseDto(any())).thenReturn(responseDto);

        testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(invoiceCaptor.capture());
        assertThat(invoiceCaptor.getValue().getPatient()).isEqualTo(patient);
        assertThat(invoiceCaptor.getValue().getCode()).startsWith("FA");
    }

    // --- AC8: CONTRACT_INVOICE_ALREADY_PAID ---

    @Test
    @DisplayName("updateStatus - contrat groupé payé → BusinessException CONTRACT_INVOICE_ALREADY_PAID")
    void updateStatus_grouped_contract_paid_throws() {
        contrat.setInvoiceUnique(true);
        order.setCode("EX26-0001");
        order.setStatus(TestOrderStatus.VALIDATED);

        Invoice paidInvoice = new Invoice();
        paidInvoice.setPaid(true);
        paidInvoice.setTotal(BigDecimal.valueOf(500));

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findFirstByContratIdOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.of(paidInvoice));

        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CONTRACT_INVOICE_ALREADY_PAID");
    }

    // --- AC8: CONTRACT_NO_INVOICE ---

    @Test
    @DisplayName("updateStatus - contrat groupé sans facture → BusinessException CONTRACT_NO_INVOICE")
    void updateStatus_grouped_contract_no_invoice_throws() {
        contrat.setInvoiceUnique(true);
        order.setCode("EX26-0001");
        order.setStatus(TestOrderStatus.VALIDATED);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(eq(BRANCH_ID)))
                .thenReturn(Optional.empty());
        when(reportRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findFirstByContratIdOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.updateStatus(ORDER_ID, "VALIDATED", USER_ID, BRANCH_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CONTRACT_NO_INVOICE");
    }
}

package com.labo.anapath.testorder;

import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.contract.Contrat;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.doctor.Doctor;
import com.labo.anapath.doctor.DoctorRepository;
import com.labo.anapath.doctor.Hospital;
import com.labo.anapath.doctor.HospitalRepository;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.test.LabTest;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.test.TypeOrder;
import com.labo.anapath.test.TypeOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestOrderServiceImplTest {

    @Mock private TestOrderRepository testOrderRepository;
    @Mock private com.labo.anapath.common.storage.FichierStockeRepository fichiers;
    @Mock private PatientRepository patientRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private ContratRepository contratRepository;
    @Mock private TypeOrderRepository typeOrderRepository;
    @Mock private LabTestRepository labTestRepository;
    @Mock private TestOrderMapper testOrderMapper;
    @Mock private ReportRepository reportRepository;
    @Mock private com.labo.anapath.report.LogReportRepository logReportRepository;
    @Mock private com.labo.anapath.finance.InvoiceRepository invoiceRepository;
    @Mock private com.labo.anapath.finance.InvoiceDetailRepository invoiceDetailRepository;
    @Mock private com.labo.anapath.user.UserRepository userRepository;
    @Mock private com.labo.anapath.setting.SettingRepository settingRepository;
    @Mock private com.labo.anapath.setting.SettingAppRepository settingAppRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    // Ajouté au service pour rendre le nom de la personne affectée dans la liste.
    @Mock private TestOrderAssignmentDetailRepository assignmentDetailRepository;

    @Mock private com.labo.anapath.biology.results.BiologyResultsLifecycle biologyResultsLifecycle;
    @InjectMocks
    private TestOrderServiceImpl testOrderService;

    private final UUID BRANCH_ID = UUID.randomUUID();
    private final UUID ORDER_ID = UUID.randomUUID();
    private final UUID PATIENT_ID = UUID.randomUUID();

    private Patient buildPatient() {
        Patient p = new Patient();
        p.setBranchId(BRANCH_ID);
        return p;
    }

    private TestOrder buildOrder(TestOrderStatus status) {
        TestOrder order = new TestOrder();
        order.setBranchId(BRANCH_ID);
        order.setStatus(status);
        order.setPrelevementDate(LocalDate.now());
        order.setPatient(buildPatient());
        return order;
    }

    private TestOrderResponseDto buildResponseDto() {
        return new TestOrderResponseDto(
                ORDER_ID, null, TestOrderStatus.PENDING, LocalDate.now(),
                null, false, null, null, null,
                PATIENT_ID, "Jean", "Dupont",
                null, null, null, null,
                null, null, null, null,
                null, null, List.of(), BRANCH_ID, LocalDateTime.now(),
                // reportId, reportStatus, reportIsDelivered, invoiceId, archive,
                // testAffiliate, option, les trois champs de facturation à un
                // tiers, puis assignedUserName et discipline.
                null, null, false, null, null, null, null,
                null, null, null,
                null, null);
    }

    @Test
    @DisplayName("create - code=null, status=PENDING, retourne le DTO")
    void create_success_codeIsNull() {
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPatientId(PATIENT_ID);
        dto.setPrelevementDate(LocalDate.now());

        TestOrderResponseDto responseDto = buildResponseDto();

        when(patientRepository.findByIdAndBranchId(PATIENT_ID, BRANCH_ID)).thenReturn(Optional.of(buildPatient()));
        when(testOrderRepository.save(any(TestOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testOrderMapper.toResponseDto(any(TestOrder.class))).thenReturn(responseDto);

        TestOrderResponseDto result = testOrderService.create(dto, BRANCH_ID);

        ArgumentCaptor<TestOrder> captor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository).save(captor.capture());

        assertThat(captor.getValue().getCode()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(TestOrderStatus.PENDING);
        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("create - patient inexistant → ResourceNotFoundException")
    void create_patientNotFound_throws404() {
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPatientId(PATIENT_ID);
        dto.setPrelevementDate(LocalDate.now());

        when(patientRepository.findByIdAndBranchId(PATIENT_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.create(dto, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create - avec détail : testName et total calculés depuis LabTest")
    void create_withDetail_computesTotalFromLabTest() {
        UUID labTestId = UUID.randomUUID();
        LabTest labTest = new LabTest();
        labTest.setPrice(new BigDecimal("100.00"));

        DetailTestOrderRequestDto detailDto = new DetailTestOrderRequestDto();
        detailDto.setLabTestId(labTestId);
        detailDto.setDiscount(10.0);

        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPatientId(PATIENT_ID);
        dto.setPrelevementDate(LocalDate.now());
        dto.setDetails(List.of(detailDto));

        when(patientRepository.findByIdAndBranchId(PATIENT_ID, BRANCH_ID)).thenReturn(Optional.of(buildPatient()));
        when(labTestRepository.findByIdAndBranchId(labTestId, BRANCH_ID)).thenReturn(Optional.of(labTest));
        when(testOrderRepository.save(any(TestOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testOrderMapper.toResponseDto(any(TestOrder.class))).thenReturn(buildResponseDto());

        testOrderService.create(dto, BRANCH_ID);

        ArgumentCaptor<TestOrder> captor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository).save(captor.capture());

        DetailTestOrder detail = captor.getValue().getDetails().get(0);
        assertThat(detail.getPrice()).isEqualTo(100.0);
        assertThat(detail.getDiscount()).isEqualTo(10.0);
        assertThat(detail.getTotal()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("findAll - retourne une page paginée")
    void findAll_returnsPaginatedResults() {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        TestOrderResponseDto dto = buildResponseDto();
        Page<TestOrder> page = new PageImpl<>(List.of(order));

        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(testOrderMapper.toResponseDto(order)).thenReturn(dto);

        PageResponse<TestOrderResponseDto> result = testOrderService.findAll(0, 20, new TestOrderFilterDto(), BRANCH_ID);

        assertThat(result.content()).hasSize(1);
    }

    @Test
    @DisplayName("findAll - porte le nom de la personne affectée, ou null si le bon ne l'est pas")
    void findAll_exposeLeNomDeLaPersonneAffectee() {
        TestOrder affecte = buildOrder(TestOrderStatus.PENDING);
        affecte.setId(UUID.randomUUID());
        TestOrder libre = buildOrder(TestOrderStatus.PENDING);
        libre.setId(UUID.randomUUID());

        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(affecte, libre)));
        when(testOrderMapper.toResponseDto(any())).thenReturn(buildResponseDto());
        // Le dépôt ne rend une ligne que pour les bons effectivement affectés :
        // l'absence dans la correspondance vaut « non affecté ».
        when(assignmentDetailRepository.findAssignedUserNames(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{affecte.getId(), "Takin Romulus"}));

        PageResponse<TestOrderResponseDto> result =
                testOrderService.findAll(0, 20, new TestOrderFilterDto(), BRANCH_ID);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).assignedUserName()).isEqualTo("Takin Romulus");
        assertThat(result.content().get(1).assignedUserName()).isNull();
    }

    @Test
    @DisplayName("findAll - un nom vide en base est traité comme une absence d'affectation")
    void findAll_nomVideEstTraiteCommeAbsent() {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        order.setId(UUID.randomUUID());

        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(testOrderMapper.toResponseDto(any())).thenReturn(buildResponseDto());
        // Un utilisateur sans prénom ni nom rendrait une chaîne d'espaces : la
        // colonne afficherait un blanc au lieu du tiret d'absence.
        when(assignmentDetailRepository.findAssignedUserNames(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{order.getId(), "   "}));

        PageResponse<TestOrderResponseDto> result =
                testOrderService.findAll(0, 20, new TestOrderFilterDto(), BRANCH_ID);

        assertThat(result.content().get(0).assignedUserName()).isNull();
    }

    @Test
    @DisplayName("findAll - filtre isUrgent transmis à la Specification")
    void findAll_withIsUrgentFilter_passesFilterToSpec() {
        Page<TestOrder> page = new PageImpl<>(List.of());
        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setIsUrgent(true);

        testOrderService.findAll(0, 20, filter, BRANCH_ID);

        verify(testOrderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("findAll - filtre patientId transmis à la Specification")
    void findAll_withPatientIdFilter_passesFilterToSpec() {
        Page<TestOrder> page = new PageImpl<>(List.of());
        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setPatientId(UUID.randomUUID());

        testOrderService.findAll(0, 20, filter, BRANCH_ID);

        verify(testOrderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("findAll - filtre status transmis à la Specification")
    void findAll_withStatusFilter_passesFilterToSpec() {
        Page<TestOrder> page = new PageImpl<>(List.of());
        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setStatus(TestOrderStatus.VALIDATED);

        testOrderService.findAll(0, 20, filter, BRANCH_ID);

        verify(testOrderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("findAll - filtre recherche code transmis à la Specification")
    void findAll_withSearchFilter_passesFilterToSpec() {
        Page<TestOrder> page = new PageImpl<>(List.of());
        when(testOrderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setSearch("EX26-0001");

        testOrderService.findAll(0, 20, filter, BRANCH_ID);

        verify(testOrderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("findById - retourne le DTO si trouvé dans la branche")
    void findById_success() {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        TestOrderResponseDto dto = buildResponseDto();

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(testOrderMapper.toResponseDto(order)).thenReturn(dto);

        TestOrderResponseDto result = testOrderService.findById(ORDER_ID, BRANCH_ID);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("findById - inexistant ou autre branche → ResourceNotFoundException")
    void findById_notFound_throws404() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.findById(ORDER_ID, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update - met à jour les champs si PENDING")
    void update_success() {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPrelevementDate(LocalDate.now().plusDays(1));
        dto.setPatientId(PATIENT_ID);
        dto.setReferenceHopital("REF-001");
        dto.setIsUrgent(true);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(testOrderRepository.save(any())).thenReturn(order);
        when(testOrderMapper.toResponseDto(order)).thenReturn(buildResponseDto());

        testOrderService.update(ORDER_ID, dto, BRANCH_ID);

        assertThat(order.getReferenceHopital()).isEqualTo("REF-001");
        assertThat(order.getIsUrgent()).isTrue();
        verify(testOrderRepository).save(order);
    }

    /**
     * Remplace un test qui bloquait sur le statut VALIDATED — règle sans
     * équivalent dans Laravel, où seule une facture payée fige les lignes.
     */
    @Test
    @DisplayName("update - facture payée → InvalidOperationException")
    void update_facturePayee_throwsInvalidOperation() {
        TestOrder order = buildOrder(TestOrderStatus.VALIDATED);
        // `buildOrder` ne pose pas d'identifiant, or le service cherche la
        // facture par `order.getId()` : sans cela le stub ne correspondrait pas.
        order.setId(ORDER_ID);
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPrelevementDate(LocalDate.now());
        dto.setPatientId(PATIENT_ID);

        com.labo.anapath.finance.Invoice facture = new com.labo.anapath.finance.Invoice();
        facture.setPaid(true);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(invoiceRepository.findByTestOrderId(ORDER_ID)).thenReturn(Optional.of(facture));

        assertThatThrownBy(() -> testOrderService.update(ORDER_ID, dto, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("payée");
    }

    @Test
    @DisplayName("update - bon validé, facture impayée → modification autorisée")
    void update_bonValideFactureImpayee_estAutorise() {
        TestOrder order = buildOrder(TestOrderStatus.VALIDATED);
        // `buildOrder` ne pose pas d'identifiant, or le service cherche la
        // facture par `order.getId()` : sans cela le stub ne correspondrait pas.
        order.setId(ORDER_ID);
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPrelevementDate(LocalDate.now());
        dto.setPatientId(PATIENT_ID);
        dto.setReferenceHopital("REF-COMPLEMENT");

        com.labo.anapath.finance.Invoice facture = new com.labo.anapath.finance.Invoice();
        facture.setPaid(false);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(invoiceRepository.findByTestOrderId(ORDER_ID)).thenReturn(Optional.of(facture));
        when(testOrderRepository.save(any())).thenReturn(order);
        when(testOrderMapper.toResponseDto(order)).thenReturn(buildResponseDto());

        testOrderService.update(ORDER_ID, dto, BRANCH_ID);

        assertThat(order.getReferenceHopital()).isEqualTo("REF-COMPLEMENT");
        verify(testOrderRepository).save(order);
    }

    @Test
    @DisplayName("update - bon introuvable ou autre branche → ResourceNotFoundException")
    void update_notFound_throws404() {
        UUID branchId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(testOrderRepository.findByIdAndBranchId(id, branchId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> testOrderService.update(id, new TestOrderRequestDto(), branchId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("delete - supprime le bon si PENDING")
    void delete_success() {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));

        testOrderService.delete(ORDER_ID, BRANCH_ID);

        verify(testOrderRepository).delete(order);
    }

    @Test
    @DisplayName("delete - bon VALIDÉ → InvalidOperationException")
    void delete_validatedOrder_throwsInvalidOperation() {
        TestOrder order = buildOrder(TestOrderStatus.VALIDATED);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> testOrderService.delete(ORDER_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("validé");
    }

    @Test
    @DisplayName("delete - inexistant ou autre branche → ResourceNotFoundException")
    void delete_notFound_throws404() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.delete(ORDER_ID, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // assignDoctor
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("assignDoctor - positionne attribuateDoctorId, assignedToUserId et assignmentDate")
    void assignDoctor_sets_fields_and_returns_dto() {
        UUID doctorId = UUID.randomUUID();
        TestOrder order = buildOrder(TestOrderStatus.VALIDATED);
        TestOrderResponseDto responseDto = buildResponseDto();

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(testOrderRepository.save(any(TestOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testOrderMapper.toResponseDto(any(TestOrder.class))).thenReturn(responseDto);

        TestOrderResponseDto result = testOrderService.assignDoctor(ORDER_ID, doctorId, BRANCH_ID);

        ArgumentCaptor<TestOrder> captor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository).save(captor.capture());

        TestOrder saved = captor.getValue();
        assertThat(saved.getAttribuateDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getAssignedToUserId()).isEqualTo(doctorId);
        assertThat(saved.getAssignmentDate()).isNotNull();
        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("assignDoctor - bon introuvable → ResourceNotFoundException")
    void assignDoctor_not_found_throws() {
        UUID doctorId = UUID.randomUUID();
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.assignDoctor(ORDER_ID, doctorId, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // markAsDelivered
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("markAsDelivered - bon VALIDATED → statut DELIVERED et report.isDelivered=true")
    void markAsDelivered_sets_delivered_and_report_flag() {
        TestOrder order = buildOrder(TestOrderStatus.VALIDATED);
        Report report = new Report();
        report.setDelivered(false);
        TestOrderResponseDto responseDto = buildResponseDto();

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(testOrderRepository.save(any(TestOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.findByTestOrderId(ORDER_ID)).thenReturn(Optional.of(report));
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testOrderMapper.toResponseDto(any(TestOrder.class))).thenReturn(responseDto);

        testOrderService.markAsDelivered(ORDER_ID, BRANCH_ID);

        ArgumentCaptor<TestOrder> orderCaptor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertThat(orderCaptor.getAllValues().get(0).getStatus()).isEqualTo(TestOrderStatus.DELIVERED);

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().isDelivered()).isTrue();
    }

    @Test
    @DisplayName("markAsDelivered - bon PENDING ou DELIVERED → InvalidOperationException")
    void markAsDelivered_only_validated_allowed() {
        TestOrder pendingOrder = buildOrder(TestOrderStatus.PENDING);
        TestOrder deliveredOrder = buildOrder(TestOrderStatus.DELIVERED);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID))
                .thenReturn(Optional.of(pendingOrder))
                .thenReturn(Optional.of(deliveredOrder));

        assertThatThrownBy(() -> testOrderService.markAsDelivered(ORDER_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("VALIDATED");

        assertThatThrownBy(() -> testOrderService.markAsDelivered(ORDER_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    @DisplayName("markAsDelivered - bon introuvable → ResourceNotFoundException")
    void markAsDelivered_not_found_throws() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.markAsDelivered(ORDER_ID, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // uploadImages
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("uploadImages - 2 fichiers stockés et liste JSON mise à jour")
    void uploadImages_storesFilesAndUpdatesJsonList() throws Exception {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        order.setFilesName(null);

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(fileStorageService.store(any())).thenReturn("file1.png", "file2.png");
        when(objectMapper.writeValueAsString(any())).thenReturn("[\"file1.png\",\"file2.png\"]");
        when(testOrderRepository.save(any())).thenReturn(order);

        // Le service lit la signature des octets (89 50 4E 47 0D 0A 1A 0A), pas
        // l'extension ni le Content-Type : un faux contenu est refusé.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile f1 = new MockMultipartFile("files_name", "img1.png", "image/png", png);
        MockMultipartFile f2 = new MockMultipartFile("files_name", "img2.png", "image/png", png);

        List<String> result = testOrderService.uploadImages(ORDER_ID, BRANCH_ID, List.<MultipartFile>of(f1, f2));

        assertThat(result).hasSize(2);
        verify(fileStorageService, org.mockito.Mockito.times(2)).store(any());
        verify(testOrderRepository).save(any());
    }

    @Test
    @DisplayName("deleteImage - index hors bornes → InvalidOperationException")
    void deleteImage_invalidIndex_throws422() throws Exception {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        order.setFilesName("[\"file.png\"]");

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(objectMapper.readValue((String) any(), any(Class.class))).thenReturn(new ArrayList<>(List.of("file.png")));

        assertThatThrownBy(() -> testOrderService.deleteImage(ORDER_ID, 5, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("deleteImage - index valide → image marquée retirée, fichier conservé")
    void deleteImage_validIndex_marquePlutotQueDeSupprimer() throws Exception {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        order.setFilesName("[\"file.png\"]");

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(objectMapper.readValue((String) any(), any(Class.class))).thenReturn(new ArrayList<>(List.of("file.png")));
        when(objectMapper.writeValueAsString(any())).thenReturn("[\"2026-08-24T10:00:00\"]");
        when(testOrderRepository.save(any())).thenReturn(order);

        testOrderService.deleteImage(ORDER_ID, 0, BRANCH_ID);

        // Le fichier reste sur le disque et l'entrée à sa place : ces clichés
        // montrent ce qui a été reçu au comptoir, et une suppression contestée
        // plus tard ne doit pas avoir effacé jusqu'au fait qu'ils aient existé.
        verify(fileStorageService, never()).delete(any());
        assertThat(order.getFilesName()).isEqualTo("[\"file.png\"]");
        assertThat(order.getFilesDeletedAt()).isNotNull();
        verify(testOrderRepository).save(any());
    }

    @Test
    @DisplayName("getImages - retourne liste avec index et URL")
    void getImages_returnsListWithIndexAndUrl() throws Exception {
        TestOrder order = buildOrder(TestOrderStatus.PENDING);
        order.setFilesName("[\"photo.png\"]");

        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.of(order));
        when(objectMapper.readValue((String) any(), any(Class.class))).thenReturn(new ArrayList<>(List.of("photo.png")));
        when(fileStorageService.getUrl("photo.png")).thenReturn("/uploads/examen_images/photo.png");

        List<ImageDto> result = testOrderService.getImages(ORDER_ID, BRANCH_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).index()).isEqualTo(0);
        assertThat(result.get(0).filename()).isEqualTo("photo.png");
        assertThat(result.get(0).url()).isEqualTo("/uploads/examen_images/photo.png");
    }

    @Test
    @DisplayName("uploadImages - bon introuvable → ResourceNotFoundException")
    void uploadImages_notFound_throws() {
        when(testOrderRepository.findByIdAndBranchId(ORDER_ID, BRANCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testOrderService.uploadImages(ORDER_ID, BRANCH_ID, List.<MultipartFile>of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

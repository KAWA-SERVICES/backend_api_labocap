package com.labo.anapath.report;

import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.setting.SettingReportTemplateRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock private ReportRepository reportRepository;
    @Mock private LogReportRepository logReportRepository;
    @Mock private TagRepository tagRepository;
    @Mock private TitleReportRepository titleReportRepository;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private UserRepository userRepository;
    @Mock private SettingReportTemplateRepository templateRepository;
    @Mock private ReportMapper reportMapper;
    @Mock private com.labo.anapath.common.email.EmailService emailService;
    @Mock private com.labo.anapath.common.email.NotificationSettings notificationSettings;
    /** Publie ReportValidatedEvent à chaque passage DRAFT → VALIDATED. */
    @Mock private ApplicationEventPublisher eventPublisher;
    /** Appareil de la session : null (web) sur un mock, donc aucune preuve exigée. */
    @Mock private com.labo.anapath.mobile.ProvenanceRequete provenanceRequete;
    /** Périmètre de validation : un mock ne refuse rien (exigerLePerimetre est un no-op). */
    @Mock private ServicePerimetreDeValidation perimetreDeValidation;
    @Mock private ReportVersionRepository reportVersionRepository;

    @InjectMocks
    private ReportServiceImpl service;

    /**
     * Faire passer un compte-rendu à VALIDATED exige le droit « validate-reports »,
     * lu dans le contexte de sécurité (voir ReportServiceImpl#exigerLeDroitDeValider).
     */
    private void connecteAvecLeDroitDeValider() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("pathologiste", null, "validate-reports"));
    }

    @AfterEach
    void nettoieLeContexteDeSecurite() {
        SecurityContextHolder.clearContext();
    }

    private final UUID BRANCH_ID = UUID.randomUUID();
    private final UUID ORDER_ID = UUID.randomUUID();
    private final UUID REPORT_ID = UUID.randomUUID();
    private final UUID TAG_1 = UUID.randomUUID();
    private final UUID TAG_2 = UUID.randomUUID();
    private final UUID USER_ID = UUID.randomUUID();

    private TestOrder buildOrder() {
        TestOrder o = new TestOrder();
        o.setCode("EX26-0001");
        return o;
    }

    private Report buildDraftReport() {
        Report r = new Report();
        r.setBranchId(BRANCH_ID);
        r.setStatus(ReportStatus.DRAFT);
        r.setTags(new ArrayList<>());
        return r;
    }

    private Tag buildTag(String name) {
        Tag t = new Tag();
        t.setName(name);
        return t;
    }

    @Test
    @DisplayName("createOrUpdate - création avec tagIds → sync tags delete+reinsert")
    void createReport_withTagIds_syncsTagsDeleteAndReinsert() {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setTestOrderId(ORDER_ID);
        dto.setTagIds(List.of(TAG_1, TAG_2));

        Tag tag1 = buildTag("Histologie");
        Tag tag2 = buildTag("Cytologie");

        when(testOrderRepository.findByIdAndBranchId(eq(ORDER_ID), any())).thenReturn(Optional.of(buildOrder()));
        when(tagRepository.findAllById(List.of(TAG_1, TAG_2))).thenReturn(List.of(tag1, tag2));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        service.createOrUpdate(dto, BRANCH_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactlyInAnyOrder(tag1, tag2);
    }

    @Test
    @DisplayName("update - status VALIDATED → signatureDate et deliveryDate posées")
    void updateReport_withStatus_VALIDATED_setsSignatureDate() {
        Report existing = buildDraftReport();
        existing.setTestOrder(buildOrder());

        ReportRequestDto dto = new ReportRequestDto();
        dto.setStatus("VALIDATED");

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(existing));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);
        connecteAvecLeDroitDeValider();

        service.update(REPORT_ID, dto, USER_ID, BRANCH_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.VALIDATED);
        assertThat(captor.getValue().getSignatureDate()).isNotNull();
        assertThat(captor.getValue().getDeliveryDate()).isNotNull();
    }

    @Test
    @DisplayName("update - signatory1Id → met à jour testOrder.assignedToUserId")
    void updateReport_withSignatory1_updatesTestOrderAssignedTo() {
        TestOrder order = buildOrder();
        Report existing = buildDraftReport();
        existing.setTestOrder(order);

        ReportRequestDto dto = new ReportRequestDto();
        dto.setStatus("VALIDATED");
        dto.setSignatory1Id(USER_ID);

        User doctor = new User();
        doctor.setFirstname("Dr");
        doctor.setLastname("Test");

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(existing));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(doctor));
        when(testOrderRepository.save(any())).thenReturn(order);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);
        connecteAvecLeDroitDeValider();

        service.update(REPORT_ID, dto, USER_ID, BRANCH_ID);

        assertThat(order.getAssignedToUserId()).isEqualTo(USER_ID);
        verify(testOrderRepository).save(order);
    }

    @Test
    @DisplayName("findAll filtré - délègue au repository avec month/year/doctorId")
    void findAll_withMonthYearFilter_returnsFilteredReports() {
        Integer month = 5;
        Integer year = 2026;
        UUID doctorId = UUID.randomUUID();

        when(reportRepository.findFiltered(eq(BRANCH_ID), eq(month), eq(year), eq(doctorId), eq("PATHOLOGY"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.findAll(0, 20, BRANCH_ID, month, year, doctorId);

        verify(reportRepository).findFiltered(eq(BRANCH_ID), eq(month), eq(year), eq(doctorId), eq("PATHOLOGY"), any(Pageable.class));
    }

    @Test
    @DisplayName("createOrUpdate - un reportId est refusé : la modification passe par update")
    void createOrUpdate_avecReportId_refuse() {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setReportId(REPORT_ID);
        dto.setContent("<p>Texte</p>");

        assertThatThrownBy(() -> service.createOrUpdate(dto, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessage(ReportServiceImpl.MODIFICATION_PAR_PUT);
        verify(reportRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Compte-rendu livré : un signataire, un motif
    // ------------------------------------------------------------------

    /** Compte-rendu livré, signé par {@code medecinId}, sans date de signature (voir compteRenduSigne). */
    private Report compteRenduLivre(UUID medecinId) {
        Report r = buildDraftReport();
        r.setStatus(ReportStatus.DELIVERED);
        r.setTestOrder(buildOrder());
        r.setDescriptionSupplementaire("");
        com.labo.anapath.user.User medecin = new com.labo.anapath.user.User();
        medecin.setId(medecinId);
        r.setSignatory1(medecin);
        return r;
    }

    private static ReportRequestDto complement(UUID signataire, String motif) {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setSignatory1Id(signataire);
        dto.setDescriptionSupplementaire("Complément après remise du résultat.");
        dto.setReason(motif);
        return dto;
    }

    /**
     * Remplace un test qui consacrait le verrou inverse.
     *
     * <p>Un complément arrive après la remise du résultat : refuser la
     * modification d'un compte-rendu livré rendait inatteignable la fonction
     * même qui sert à ce moment-là. Mais le résultat est sorti : seul un
     * signataire décide, et dit pourquoi.</p>
     */
    @Test
    @DisplayName("update - livré : un signataire avec motif modifie, et le motif est journalisé")
    void update_livre_parSignataireAvecMotif_accepte() {
        UUID medecinId = UUID.randomUUID();
        Report delivered = compteRenduLivre(medecinId);
        // Signé au sens de l'empreinte : la trace « après signature » porte le motif.
        delivered.setSignatureDate(LocalDateTime.now().minusDays(2));

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(delivered));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);
        when(userRepository.findById(medecinId)).thenReturn(Optional.of(delivered.getSignatory1()));

        service.update(REPORT_ID, complement(medecinId, "Complément demandé par le prescripteur."),
                medecinId, BRANCH_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getDescriptionSupplementaire())
                .isEqualTo("Complément après remise du résultat.");
        LogReport trace = tracesEnregistrees().stream()
                .filter(l -> ReportServiceImpl.ACTION_APRES_SIGNATURE.equals(l.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucune trace après signature enregistrée"));
        assertThat(trace.getDescription())
                .contains("Motif : Complément demandé par le prescripteur.")
                .contains("Champs : Description complémentaire");
        // L'état d'avant est conservé, numéroté à la suite.
        ArgumentCaptor<ReportVersion> version = ArgumentCaptor.forClass(ReportVersion.class);
        verify(reportVersionRepository).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        assertThat(version.getValue().getStatus()).isEqualTo(ReportStatus.DELIVERED);
        assertThat(version.getValue().getDescriptionSupplementaire()).isEmpty();
    }

    @Test
    @DisplayName("update - livré : sans motif → refusé avant toute écriture")
    void update_livre_sansMotif_refuse() {
        UUID medecinId = UUID.randomUUID();
        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(compteRenduLivre(medecinId)));

        assertThatThrownBy(() -> service.update(REPORT_ID, complement(medecinId, "trop court"),
                medecinId, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("motif");
        verify(reportRepository, never()).save(any());
    }

    // La levée de la restriction aux signataires est éprouvée de bout en bout
    // dans `VersionsDeCompteRenduIT`, avec une vraie base et le vrai rôle
    // secrétariat. La rejouer ici exigerait d'échafauder tout le chemin
    // d'écriture en bouchons : on y éprouverait les bouchons, pas la règle.

    // ===== Tests story 3-7 — Validation, signature, livraison =====

    @Test
    @DisplayName("validate - DRAFT → VALIDATED + signatureDate posée")
    void validate_setsStatusValidatedAndSignatureDate() {
        Report report = buildDraftReport();
        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        service.validate(REPORT_ID, USER_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.VALIDATED);
        assertThat(captor.getValue().getSignatureDate()).isNotNull();
    }

    @Test
    @DisplayName("validate - déjà VALIDATED → InvalidOperationException")
    void validate_alreadyValidated_throws() {
        Report report = buildDraftReport();
        report.setStatus(ReportStatus.VALIDATED);
        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.validate(REPORT_ID, USER_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("markDelivered - isDelivered=true + deliveryDate posée")
    void markDelivered_setsIsDeliveredTrueAndLogsAction() {
        Report report = buildDraftReport();
        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        service.markDelivered(REPORT_ID, USER_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isDelivered()).isTrue();
        assertThat(captor.getValue().getDeliveryDate()).isNotNull();
    }

    @Test
    @DisplayName("markInformed - isCalled=true + callDate posée")
    void markInformed_setsIsCalledTrueAndLogsAction() {
        Report report = buildDraftReport();
        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        service.markInformed(REPORT_ID, USER_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isCalled()).isTrue();
        assertThat(captor.getValue().getCallDate()).isNotNull();
    }

    @Test
    @DisplayName("storeSignature - RÈGLE R5 : isDelivered ET isCalled positionnés simultanément")
    void storeSignature_setsIsDeliveredAndIsCalledSimultaneously() {
        Report report = buildDraftReport();
        StoreSignatureRequestDto dto = new StoreSignatureRequestDto();
        dto.setSignatorName("Jean Dupont");
        dto.setSignature("data:image/png;base64,abc123");

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        service.storeSignature(REPORT_ID, dto, USER_ID);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        Report saved = captor.getValue();
        assertThat(saved.isDelivered()).isTrue();
        assertThat(saved.isCalled()).isTrue();
        assertThat(saved.getRetrieverName()).isEqualTo("Jean Dupont");
        assertThat(saved.getRetrieverSignature()).isEqualTo("data:image/png;base64,abc123");
        assertThat(saved.getDeliveryDate()).isNotNull();
        assertThat(saved.getCallDate()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Traçabilité des modifications après signature
    // ------------------------------------------------------------------

    /** Compte-rendu déjà signé : un médecin apposé et une date de signature. */
    private Report compteRenduSigne(UUID medecinId) {
        Report r = buildDraftReport();
        r.setStatus(ReportStatus.VALIDATED);
        r.setSignatureDate(java.time.LocalDateTime.now().minusDays(1));
        r.setContent("<p>Texte d'origine</p>");
        com.labo.anapath.user.User medecin = new com.labo.anapath.user.User();
        medecin.setId(medecinId);
        r.setSignatory1(medecin);
        return r;
    }

    /** Reprend le compte-rendu à l'identique ; l'appelant modifie ce qu'il veut. */
    private ReportRequestDto dtoMiroir(UUID medecinId, String contenu) {
        ReportRequestDto dto = new ReportRequestDto();
        dto.setReportId(REPORT_ID);
        dto.setStatus("VALIDATED");
        dto.setContent(contenu);
        dto.setSignatory1Id(medecinId);
        return dto;
    }

    private List<LogReport> tracesEnregistrees() {
        ArgumentCaptor<LogReport> captor = ArgumentCaptor.forClass(LogReport.class);
        verify(logReportRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("update - contenu retouché après signature → trace et alerte administrateurs")
    void update_apresSignature_traceEtAlerte() {
        UUID medecinId = UUID.randomUUID();
        Report report = compteRenduSigne(medecinId);
        report.setCode("CO26-0001");

        com.labo.anapath.user.User auteur = new com.labo.anapath.user.User();
        auteur.setId(USER_ID);
        auteur.setFirstname("Coralie");
        auteur.setLastname("OGOUSSAN");

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);
        when(userRepository.findById(medecinId))
                .thenReturn(Optional.of(report.getSignatory1()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(auteur));
        when(notificationSettings.labName(BRANCH_ID)).thenReturn("CAAP");
        when(notificationSettings.adminEmails(BRANCH_ID))
                .thenReturn(List.of("admin@caap.bj", "direction@caap.bj"));

        service.update(REPORT_ID, dtoMiroir(medecinId, "<p>Texte corrigé</p>"),
                USER_ID, BRANCH_ID);

        LogReport trace = tracesEnregistrees().stream()
                .filter(l -> ReportServiceImpl.ACTION_APRES_SIGNATURE.equals(l.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucune trace après signature enregistrée"));
        assertThat(trace.getDescription())
                .contains("OGOUSSAN Coralie")
                .contains("Contenu macroscopique");
        assertThat(trace.getUser()).isEqualTo(auteur);

        // Chaque administrateur configuré est averti, pas seulement le premier.
        verify(emailService).sendPostSignatureChangeAlert(eq("admin@caap.bj"),
                eq("CO26-0001"), any(), any(), eq("OGOUSSAN Coralie"), any(), any(), eq("CAAP"));
        verify(emailService).sendPostSignatureChangeAlert(eq("direction@caap.bj"),
                eq("CO26-0001"), any(), any(), eq("OGOUSSAN Coralie"), any(), any(), eq("CAAP"));

        // Validé et retouché : l'ancien texte survit dans une version.
        ArgumentCaptor<ReportVersion> version = ArgumentCaptor.forClass(ReportVersion.class);
        verify(reportVersionRepository).save(version.capture());
        assertThat(version.getValue().getContent()).isEqualTo("<p>Texte d'origine</p>");
        assertThat(version.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("update - réenregistrement à l'identique → aucune alerte")
    void update_apresSignature_sansChangement_neTracePas() {
        UUID medecinId = UUID.randomUUID();
        Report report = compteRenduSigne(medecinId);

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);
        when(userRepository.findById(medecinId))
                .thenReturn(Optional.of(report.getSignatory1()));
        // Lu pour l'auteur de la version — qui ne sera pas enregistrée.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.update(REPORT_ID, dtoMiroir(medecinId, "<p>Texte d'origine</p>"),
                USER_ID, BRANCH_ID);

        // On vise l'action précise plutôt qu'un décompte de sauvegardes : le
        // journal ordinaire (« Mettre à jour ») n'a rien à voir ici.
        verify(logReportRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                l -> ReportServiceImpl.ACTION_APRES_SIGNATURE.equals(l.getAction())));
        verify(emailService, never()).sendPostSignatureChangeAlert(
                any(), any(), any(), any(), any(), any(), any(), any());
        // Rien n'a changé : pas de version non plus.
        verify(reportVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("update - compte rendu non signé → aucune alerte")
    void update_sansSignature_neTracePas() {
        Report report = buildDraftReport();
        report.setContent("<p>Brouillon</p>");

        when(reportRepository.findByIdAndBranchId(eq(REPORT_ID), any())).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportMapper.toResponseDto(any())).thenReturn(null);

        ReportRequestDto dto = new ReportRequestDto();
        dto.setReportId(REPORT_ID);
        dto.setStatus("DRAFT");
        dto.setContent("<p>Brouillon retouché</p>");

        service.update(REPORT_ID, dto, USER_ID, BRANCH_ID);

        // On vise l'action précise plutôt qu'un décompte de sauvegardes : le
        // journal ordinaire (« Mettre à jour ») n'a rien à voir ici.
        verify(logReportRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                l -> ReportServiceImpl.ACTION_APRES_SIGNATURE.equals(l.getAction())));
        verify(emailService, never()).sendPostSignatureChangeAlert(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(reportVersionRepository, never()).save(any());
    }
}

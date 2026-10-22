package com.labo.anapath.biology.report;

import com.labo.anapath.biology.results.AvancementDuCompteRendu;
import com.labo.anapath.biology.results.BiologyAnalysisResult;
import com.labo.anapath.biology.results.BiologyAnalysisResultRepository;
import com.labo.anapath.biology.results.BiologyAnalysisStatus;
import com.labo.anapath.biology.results.BiologyValidationMode;
import com.labo.anapath.biology.results.ReglagesDeBiologie;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.email.EmailService;
import com.labo.anapath.common.email.NotificationSettings;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.mobile.MobileDeviceRepository;
import com.labo.anapath.mobile.ProvenanceRequete;
import com.labo.anapath.mobile.SignatureAppareil;
import com.labo.anapath.report.LogReport;
import com.labo.anapath.report.LogReportRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportMapper;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportService;
import com.labo.anapath.report.ReportServiceImpl;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.report.ReportValidatedEvent;
import com.labo.anapath.report.TagRepository;
import com.labo.anapath.report.TitleReportRepository;
import com.labo.anapath.report.ValidationSigneeDto;
import com.labo.anapath.setting.SettingReportTemplateRepository;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignmentDetailRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation biologique et réouverture : gardes, transitions, et un seul avis au
 * patient par validation.
 */
class BiologyReportServiceImplTest {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final BiologyAnalysisResultRepository analysisRepo = mock(BiologyAnalysisResultRepository.class);
    private final ReglagesDeBiologie reglages = mock(ReglagesDeBiologie.class);
    private final AvancementDuCompteRendu avancement = mock(AvancementDuCompteRendu.class);

    private Report report;
    private TestOrder bon;
    private User biologiste;

    @BeforeEach
    void preparer() {
        bon = new TestOrder();
        bon.setId(UUID.randomUUID());
        bon.setBranchId(BRANCH);
        bon.setCode("26-0100");
        bon.setDiscipline(Discipline.BIOLOGY);
        bon.setStatus(TestOrderStatus.VALIDATED);

        report = new Report();
        report.setId(UUID.randomUUID());
        report.setBranchId(BRANCH);
        report.setCode("CO26-0100");
        report.setDiscipline(Discipline.BIOLOGY);
        report.setStatus(ReportStatus.PENDING_REVIEW);
        report.setTestOrder(bon);

        biologiste = new User();
        biologiste.setId(USER);
        biologiste.setFirstname("Awa");
        biologiste.setLastname("BIO");

        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));
        when(reportRepository.save(any(Report.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(USER)).thenReturn(Optional.of(biologiste));
        when(reglages.mode(BRANCH)).thenReturn(BiologyValidationMode.TWO_STEP);
        analyses(BiologyAnalysisStatus.TECH_VALIDATED, BiologyAnalysisStatus.TECH_VALIDATED);
    }

    private void analyses(BiologyAnalysisStatus... statuts) {
        when(analysisRepo.findByTestOrderId(bon.getId())).thenReturn(Arrays.stream(statuts).map(s -> {
            BiologyAnalysisResult r = new BiologyAnalysisResult();
            r.setStatus(s);
            return r;
        }).toList());
    }

    // =====================================================================================
    @Nested
    @DisplayName("gardes et transitions (cœur commun simulé)")
    class Gardes {

        private final ReportService reportService = mock(ReportService.class);
        private final BiologyReportServiceImpl service = new BiologyReportServiceImpl(reportRepository,
                reportService, reportMapper, userRepository, analysisRepo, reglages, avancement);

        @Test
        @DisplayName("validate : compte-rendu prêt → le biologiste signe, puis le cœur commun valide")
        void valideUnCompteRenduPret() {
            ValidationSigneeDto preuve = new ValidationSigneeDto(UUID.randomUUID(), LocalDateTime.now(), "sig");

            service.validate(report.getId(), USER, BRANCH, preuve);

            assertThat(report.getSignatory1()).isSameAs(biologiste);
            verify(reportService).validerCompteRendu(report, USER, preuve);
        }

        @Test
        @DisplayName("validate : ONE_STEP accepte des analyses seulement saisies")
        void uneEtape() {
            when(reglages.mode(BRANCH)).thenReturn(BiologyValidationMode.ONE_STEP);
            analyses(BiologyAnalysisStatus.ENTERED, BiologyAnalysisStatus.TECH_VALIDATED);

            service.validate(report.getId(), USER, BRANCH, null);

            verify(reportService).validerCompteRendu(report, USER, null);
        }

        @Test
        @DisplayName("validate : PENDING_REVIEW mais une analyse non validée techniquement (TWO_STEP) → refus")
        void statutPretMaisAnalysesNon() {
            analyses(BiologyAnalysisStatus.TECH_VALIDATED, BiologyAnalysisStatus.ENTERED);

            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("validées techniquement");
            verify(reportService, never()).validerCompteRendu(any(), any(), any());
            assertThat(report.getSignatory1()).isNull();
        }

        @Test
        @DisplayName("validate : DRAFT → refus ; VALIDATED/DELIVERED → déjà validé")
        void statutsRefuses() {
            report.setStatus(ReportStatus.DRAFT);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("pas prêt");
            report.setStatus(ReportStatus.VALIDATED);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(InvalidOperationException.class).hasMessageContaining("déjà validé");
            report.setStatus(ReportStatus.DELIVERED);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(InvalidOperationException.class);
            verify(reportService, never()).validerCompteRendu(any(), any(), any());
        }

        @Test
        @DisplayName("validate : compte-rendu d'anatomie pathologique → refus ; autre succursale → 404")
        void disciplineEtSuccursale() {
            report.setDiscipline(Discipline.PATHOLOGY);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("anatomie pathologique");
            report.setDiscipline(Discipline.BIOLOGY);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, UUID.randomUUID(), null))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(reportService, never()).validerCompteRendu(any(), any(), any());
        }

        @Test
        @DisplayName("reopen : VALIDATED → DRAFT, signature effacée, journal, puis recalcul de l'état")
        void rouvre() {
            report.setStatus(ReportStatus.VALIDATED);
            report.setSignatory1(biologiste);
            report.setSignatureDate(LocalDateTime.now());
            report.setDeliveryDate(LocalDateTime.now());
            report.setSigningDeviceId(UUID.randomUUID());
            report.setDeviceSignature("sig");
            report.setDeviceSignedAt(LocalDateTime.now());

            service.reopen(report.getId(), USER, BRANCH);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
            assertThat(report.getSignatory1()).isNull();
            assertThat(report.getSignatureDate()).isNull();
            assertThat(report.getDeliveryDate()).isNull();
            assertThat(report.getSigningDeviceId()).isNull();
            assertThat(report.getDeviceSignature()).isNull();
            assertThat(report.getDeviceSignedAt()).isNull();
            InOrder ordre = inOrder(reportRepository, avancement);
            ordre.verify(reportRepository).save(report);
            ordre.verify(avancement).journaliser(eq(report), eq(USER),
                    eq(BiologyReportServiceImpl.ACTION_REOUVERTURE), anyString());
            ordre.verify(avancement).recalculer(bon.getId(), BRANCH, USER);
            // Les analyses gardent leur état : aucune n'est touchée.
            verify(analysisRepo, never()).save(any());
        }

        @Test
        @DisplayName("reopen : livré (statut ou drapeau) → refus ; non validé → refus")
        void reouvertureRefusee() {
            report.setStatus(ReportStatus.DELIVERED);
            assertThatThrownBy(() -> service.reopen(report.getId(), USER, BRANCH))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("remis");
            report.setStatus(ReportStatus.VALIDATED);
            report.setDelivered(true);
            assertThatThrownBy(() -> service.reopen(report.getId(), USER, BRANCH))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("remis");
            report.setDelivered(false);
            report.setStatus(ReportStatus.PENDING_REVIEW);
            assertThatThrownBy(() -> service.reopen(report.getId(), USER, BRANCH))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("validé");
            verify(reportRepository, never()).save(any());
            verify(avancement, never()).recalculer(any(), any(), any());
        }

        @Test
        @DisplayName("conclusion : enregistrée avant validation, refusée après")
        void conclusion() {
            service.updateConclusion(report.getId(), "  Bilan normal.  ", USER, BRANCH);
            assertThat(report.getComment()).isEqualTo("Bilan normal.");
            service.updateConclusion(report.getId(), " ", USER, BRANCH);
            assertThat(report.getComment()).isNull();

            report.setStatus(ReportStatus.VALIDATED);
            assertThatThrownBy(() -> service.updateConclusion(report.getId(), "x", USER, BRANCH))
                    .isInstanceOf(InvalidOperationException.class);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("avec le vrai cœur de validation (ReportServiceImpl)")
    class CoeurCommun {

        private final LogReportRepository logRepo = mock(LogReportRepository.class);
        private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        private final ProvenanceRequete provenance = mock(ProvenanceRequete.class);
        private final ReportServiceImpl coeur = new ReportServiceImpl(reportRepository, logRepo,
                mock(TagRepository.class), mock(TitleReportRepository.class), mock(TestOrderRepository.class),
                userRepository, mock(SettingReportTemplateRepository.class), reportMapper,
                mock(EmailService.class), mock(NotificationSettings.class), publisher,
                mock(MobileDeviceRepository.class), mock(SignatureAppareil.class), provenance,
                mock(TestOrderAssignmentDetailRepository.class),
                // Périmètre de validation : ce test porte sur le cœur commun et
                // non sur la règle par type d'examen. Un mock la laisse passer.
                mock(com.labo.anapath.report.ServicePerimetreDeValidation.class),
                mock(com.labo.anapath.report.JournalDesRefus.class),
                new ObjectMapper(),
                mock(com.labo.anapath.report.ReportVersionRepository.class));
        private final BiologyReportServiceImpl service = new BiologyReportServiceImpl(reportRepository,
                coeur, reportMapper, userRepository, analysisRepo, reglages, avancement);

        @Test
        @DisplayName("validation : VALIDATED, date de signature, journal « Validé », événement publié UNE fois")
        void evenementUneFois() {
            service.validate(report.getId(), USER, BRANCH, null);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.VALIDATED);
            assertThat(report.getSignatureDate()).isNotNull();
            assertThat(report.getSignatory1()).isSameAs(biologiste);
            ArgumentCaptor<Object> evenements = ArgumentCaptor.forClass(Object.class);
            verify(publisher, times(1)).publishEvent(evenements.capture());
            assertThat(evenements.getValue()).isEqualTo(new ReportValidatedEvent(report.getId(), USER));
            ArgumentCaptor<LogReport> journal = ArgumentCaptor.forClass(LogReport.class);
            verify(logRepo).save(journal.capture());
            assertThat(journal.getValue().getAction()).isEqualTo("Validé");
        }

        @Test
        @DisplayName("validation rejouée sur un compte-rendu déjà validé : refusée, aucun second avis")
        void pasDeSecondAvis() {
            service.validate(report.getId(), USER, BRANCH, null);
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(InvalidOperationException.class);
            verify(publisher, times(1)).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("session ouverte depuis un téléphone enrôlé sans preuve : refusée comme en anatomie pathologique")
        void mobileSansPreuve() {
            when(provenance.appareilCourant()).thenReturn(UUID.randomUUID());
            assertThatThrownBy(() -> service.validate(report.getId(), USER, BRANCH, null))
                    .isInstanceOf(AccessDeniedException.class);
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("le point d'entrée d'anatomie pathologique refuse toujours un compte-rendu de biologie")
        void validateAnatomiePathologiqueRefuse() {
            // Le cœur commun charge le compte-rendu dans l'agence de la requête.
            when(reportRepository.findByIdAndBranchId(org.mockito.ArgumentMatchers.eq(report.getId()), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(report));
            assertThatThrownBy(() -> coeur.validate(report.getId(), USER, null))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("biologie");
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("remise et signature du récupérateur, communes : fonctionnent pour la biologie")
        void remiseCommune() {
            // Le cœur commun charge le compte-rendu dans l'agence de la requête.
            when(reportRepository.findByIdAndBranchId(org.mockito.ArgumentMatchers.eq(report.getId()), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(report));
            service.validate(report.getId(), USER, BRANCH, null);

            coeur.deliver(report.getId(), "Mme KORA", USER);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.DELIVERED);
            assertThat(bon.getStatus()).isEqualTo(TestOrderStatus.DELIVERED);

            report.setStatus(ReportStatus.VALIDATED);
            coeur.markDelivered(report.getId(), USER);
            assertThat(report.isDelivered()).isTrue();
            coeur.markInformed(report.getId(), USER);
            assertThat(report.isCalled()).isTrue();

            // Remis : la réouverture est refusée.
            assertThatThrownBy(() -> service.reopen(report.getId(), USER, BRANCH))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("valider, rouvrir, revalider : un avis par validation")
        void rouvrirPuisRevalider() {
            service.validate(report.getId(), USER, BRANCH, null);
            service.reopen(report.getId(), USER, BRANCH);
            // Le recalcul (simulé ici) remet le bon prêt en relecture.
            report.setStatus(ReportStatus.PENDING_REVIEW);
            service.validate(report.getId(), USER, BRANCH, null);

            verify(publisher, times(2)).publishEvent(any(ReportValidatedEvent.class));
            assertThat(report.getSignatory1()).isSameAs(biologiste);
            assertThat(report.getSignatureDate()).isNotNull();
        }
    }
}

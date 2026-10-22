package com.labo.anapath.testorder;

import com.labo.anapath.biology.BiologyResultsGuard;
import com.labo.anapath.client.ClientRepository;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.module.ModulesProperties;
import com.labo.anapath.contract.Contrat;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.contract.DetailsContrat;
import com.labo.anapath.contract.DetailsContratRepository;
import com.labo.anapath.doctor.DoctorRepository;
import com.labo.anapath.doctor.HospitalRepository;
import com.labo.anapath.finance.Invoice;
import com.labo.anapath.finance.InvoiceDetail;
import com.labo.anapath.finance.InvoiceDetailRepository;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.LogReportRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.setting.Setting;
import com.labo.anapath.setting.SettingAppRepository;
import com.labo.anapath.setting.SettingRepository;
import com.labo.anapath.test.CategoryTest;
import com.labo.anapath.test.LabTest;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.test.TypeOrderRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Bons de biologie dans la chaîne commune des bons d'examen : création, modification,
 * validation et facturation.
 *
 * <p>La facturation, les remises de contrat et la caisse ne connaissent pas la
 * discipline : les derniers cas vérifient qu'un bon de biologie produit exactement
 * les mêmes lignes de facture qu'un bon d'anatomie pathologique identique.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BonsDeBiologieTest {

    @Mock private TestOrderRepository testOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private ContratRepository contratRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private DetailsContratRepository detailsContratRepository;
    @Mock private TypeOrderRepository typeOrderRepository;
    @Mock private LabTestRepository labTestRepository;
    @Mock private TestOrderMapper testOrderMapper;
    @Mock private ReportRepository reportRepository;
    @Mock private LogReportRepository logReportRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceDetailRepository invoiceDetailRepository;
    @Mock private UserRepository userRepository;
    @Mock private SettingRepository settingRepository;
    @Mock private SettingAppRepository settingAppRepository;
    @Mock private TestOrderAssignmentDetailRepository assignmentDetailRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock private BiologyResultsGuard biologyResultsGuard;
    @Mock private com.labo.anapath.biology.results.BiologyResultsLifecycle biologyResultsLifecycle;
    @Spy private ModulesProperties modules = new ModulesProperties();

    @InjectMocks
    private TestOrderServiceImpl service;

    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID CONTRAT_ID = UUID.randomUUID();

    private Patient patient;
    private Contrat contrat;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setFirstname("Awa");
        patient.setLastname("Kora");
        when(patientRepository.findByIdAndBranchId(PATIENT_ID, BRANCH_ID)).thenReturn(Optional.of(patient));

        contrat = new Contrat();
        contrat.setId(CONTRAT_ID);
        contrat.setNbrTests(-1);
        contrat.setInvoiceUnique(false);
        when(contratRepository.findByIdAndBranchId(CONTRAT_ID, BRANCH_ID)).thenReturn(Optional.of(contrat));

        when(testOrderRepository.save(any())).thenAnswer(inv -> {
            TestOrder o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
    }

    private LabTest analyse(String nom, Discipline discipline, long prix) {
        LabTest t = new LabTest();
        t.setId(UUID.randomUUID());
        t.setBranchId(BRANCH_ID);
        t.setName(nom);
        t.setPrice(BigDecimal.valueOf(prix));
        t.setDiscipline(discipline);
        when(labTestRepository.findByIdAndBranchId(t.getId(), BRANCH_ID)).thenReturn(Optional.of(t));
        return t;
    }

    private DetailTestOrderRequestDto ligne(LabTest t, Double remise) {
        DetailTestOrderRequestDto d = new DetailTestOrderRequestDto();
        d.setLabTestId(t.getId());
        d.setDiscount(remise);
        return d;
    }

    private TestOrderRequestDto demande(Discipline discipline, LabTest... analyses) {
        TestOrderRequestDto dto = new TestOrderRequestDto();
        dto.setPatientId(PATIENT_ID);
        dto.setPrelevementDate(LocalDate.now());
        dto.setDiscipline(discipline);
        for (LabTest t : analyses) dto.getDetails().add(ligne(t, null));
        return dto;
    }

    private TestOrder bonExistant(Discipline discipline, LabTest... analyses) {
        TestOrder o = new TestOrder();
        o.setId(UUID.randomUUID());
        o.setBranchId(BRANCH_ID);
        o.setDiscipline(discipline);
        o.setPatient(patient);
        o.setPrelevementDate(LocalDate.now());
        for (LabTest t : analyses) {
            DetailTestOrder d = new DetailTestOrder();
            d.setTestOrder(o);
            d.setLabTest(t);
            d.setTestName(t.getName());
            d.setPrice(t.getPrice().doubleValue());
            d.setTotal(t.getPrice().doubleValue());
            o.getDetails().add(d);
        }
        when(testOrderRepository.findByIdAndBranchId(o.getId(), BRANCH_ID)).thenReturn(Optional.of(o));
        return o;
    }

    private TestOrder bonEnregistre() {
        ArgumentCaptor<TestOrder> captor = ArgumentCaptor.forClass(TestOrder.class);
        verify(testOrderRepository).save(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------------------------
    // Création
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Creation {

        @Test
        @DisplayName("bon de biologie, module actif : créé en BIOLOGY, sans type de bon")
        void biologie_moduleActif_cree() {
            modules.setBiology(true);
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);

            service.create(demande(Discipline.BIOLOGY, nfs), BRANCH_ID);

            TestOrder bon = bonEnregistre();
            assertThat(bon.getDiscipline()).isEqualTo(Discipline.BIOLOGY);
            assertThat(bon.getTypeOrder()).isNull();
            assertThat(bon.getStatus()).isEqualTo(TestOrderStatus.PENDING);
            assertThat(bon.getDetails()).singleElement()
                    .satisfies(d -> {
                        assertThat(d.getLabTest()).isSameAs(nfs);
                        assertThat(d.getPrice()).isEqualTo(5000.0);
                        assertThat(d.getTotal()).isEqualTo(5000.0);
                    });
            verifyNoInteractions(typeOrderRepository);
        }

        @Test
        @DisplayName("bon de biologie, module inactif : refusé avant toute écriture")
        void biologie_moduleInactif_refuse() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);

            assertThatThrownBy(() -> service.create(demande(Discipline.BIOLOGY, nfs), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("module Biologie n'est pas activé");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("bon de biologie avec une analyse d'anatomie pathologique : refusé")
        void biologie_analyseDePathologie_refuse() {
            modules.setBiology(true);
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);

            assertThatThrownBy(() -> service.create(demande(Discipline.BIOLOGY, nfs, biopsie), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ne mélange pas les disciplines")
                    .hasMessageContaining("« Biopsie gastrique »");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("bon d'anatomie pathologique avec une analyse de biologie : refusé")
        void pathologie_analyseDeBiologie_refuse() {
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);

            // Sans discipline : un client d'anatomie pathologique.
            assertThatThrownBy(() -> service.create(demande(null, biopsie, nfs), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("« NFS » relève de la biologie");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("bon de biologie avec un type de bon : refusé")
        void biologie_typeDeBon_refuse() {
            modules.setBiology(true);
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            TestOrderRequestDto dto = demande(Discipline.BIOLOGY, nfs);
            dto.setTypeOrderId(UUID.randomUUID());

            assertThatThrownBy(() -> service.create(dto, BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ne porte pas de type de bon");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("anatomie pathologique sans discipline ni type de bon : inchangé, module jamais consulté")
        void pathologie_sansDiscipline_inchange() {
            // Le type de bon n'a jamais été exigé par le serveur (seul le formulaire
            // web le rend obligatoire) : ce comportement reste celui d'avant.
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);

            service.create(demande(null, biopsie), BRANCH_ID);

            TestOrder bon = bonEnregistre();
            assertThat(bon.getDiscipline()).isEqualTo(Discipline.PATHOLOGY);
            assertThat(bon.getTypeOrder()).isNull();
            verify(modules, never()).isBiology();
            verifyNoInteractions(biologyResultsGuard);
        }

        @Test
        @DisplayName("anatomie pathologique avec type de bon, module inactif : inchangé")
        void pathologie_typeDeBon_inchange() {
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);
            com.labo.anapath.test.TypeOrder type = new com.labo.anapath.test.TypeOrder();
            type.setId(UUID.randomUUID());
            when(typeOrderRepository.findByIdAndBranchId(type.getId(), BRANCH_ID)).thenReturn(Optional.of(type));
            TestOrderRequestDto dto = demande(Discipline.PATHOLOGY, biopsie);
            dto.setTypeOrderId(type.getId());

            service.create(dto, BRANCH_ID);

            TestOrder bon = bonEnregistre();
            assertThat(bon.getDiscipline()).isEqualTo(Discipline.PATHOLOGY);
            assertThat(bon.getTypeOrder()).isSameAs(type);
        }
    }

    // -------------------------------------------------------------------------
    // Modification
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Modification {

        @Test
        @DisplayName("changer la discipline d'un bon : refusé, dans les deux sens")
        void changementDeDiscipline_refuse() {
            TestOrder patho = bonExistant(Discipline.PATHOLOGY);
            TestOrder bio = bonExistant(Discipline.BIOLOGY);

            assertThatThrownBy(() -> service.update(patho.getId(), demande(Discipline.BIOLOGY), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("discipline d'un bon d'examen ne se modifie pas");
            assertThatThrownBy(() -> service.update(bio.getId(), demande(Discipline.PATHOLOGY), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("discipline d'un bon d'examen ne se modifie pas");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("même discipline ou discipline absente : accepté")
        void memeDiscipline_accepte() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs);

            service.update(bio.getId(), demande(Discipline.BIOLOGY, nfs), BRANCH_ID);
            service.update(bio.getId(), demande(null, nfs), BRANCH_ID);

            assertThat(bio.getDiscipline()).isEqualTo(Discipline.BIOLOGY);
        }

        @Test
        @DisplayName("biologie : retirer une analyse qui porte des résultats est refusé, rien n'est touché")
        void biologie_retraitAvecResultats_refuse() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs, glycemie);
            when(biologyResultsGuard.analysesAvecResultats(eq(bio.getId()), anyCollection()))
                    .thenReturn(Set.of(glycemie.getId()));

            assertThatThrownBy(() -> service.update(bio.getId(), demande(null, nfs), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("« Glycémie »")
                    .hasMessageContaining("ne peut plus être retirée");
            assertThat(bio.getDetails()).hasSize(2);
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("biologie : retirer une analyse sans résultat est accepté ; seules les retirées sont soumises")
        @SuppressWarnings("unchecked")
        void biologie_retraitSansResultats_accepte() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs, glycemie);
            when(biologyResultsGuard.analysesAvecResultats(any(), anyCollection())).thenReturn(Set.of());

            service.update(bio.getId(), demande(null, nfs), BRANCH_ID);

            ArgumentCaptor<Collection<UUID>> soumises = ArgumentCaptor.forClass(Collection.class);
            verify(biologyResultsGuard).analysesAvecResultats(eq(bio.getId()), soumises.capture());
            assertThat(soumises.getValue()).containsExactly(glycemie.getId());
            assertThat(bio.getDetails()).singleElement()
                    .satisfies(d -> assertThat(d.getLabTest()).isSameAs(nfs));
        }

        @Test
        @DisplayName("biologie : un ajout sans retrait ne consulte pas les résultats")
        void biologie_ajoutSeul_gardeNonConsultee() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs);

            service.update(bio.getId(), demande(null, nfs, glycemie), BRANCH_ID);

            verifyNoInteractions(biologyResultsGuard);
            assertThat(bio.getDetails()).hasSize(2);
        }

        @Test
        @DisplayName("anatomie pathologique : le retrait d'une analyse ne consulte jamais les résultats de biologie")
        void pathologie_retrait_gardeJamaisConsultee() {
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);
            LabTest frottis = analyse("Frottis", Discipline.PATHOLOGY, 8000);
            TestOrder patho = bonExistant(Discipline.PATHOLOGY, biopsie, frottis);

            service.update(patho.getId(), demande(null, biopsie), BRANCH_ID);

            verifyNoInteractions(biologyResultsGuard);
            verify(modules, never()).isBiology();
            assertThat(patho.getDetails()).hasSize(1);
        }

        @Test
        @DisplayName("biologie : ajouter une analyse d'anatomie pathologique est refusé")
        void biologie_ajoutAnalyseDePathologie_refuse() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs);

            assertThatThrownBy(() -> service.update(bio.getId(), demande(null, nfs, biopsie), BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ne mélange pas les disciplines");
            verify(testOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("biologie : poser un type de bon est refusé")
        void biologie_typeDeBon_refuse() {
            TestOrder bio = bonExistant(Discipline.BIOLOGY);
            TestOrderRequestDto dto = demande(null);
            dto.setTypeOrderId(UUID.randomUUID());

            assertThatThrownBy(() -> service.update(bio.getId(), dto, BRANCH_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ne porte pas de type de bon");
        }
    }

    // -------------------------------------------------------------------------
    // Validation (updateStatus) et facturation
    // -------------------------------------------------------------------------

    /** Branche les dépôts qu'emprunte la validation d'un bon. */
    private void preparerLaValidation(TestOrder bon) {
        when(testOrderRepository.findByIdAndBranchId(bon.getId(), BRANCH_ID)).thenReturn(Optional.of(bon));
        when(settingAppRepository.findByKeyAndBranchId(anyString(), eq(BRANCH_ID))).thenReturn(Optional.empty());
        when(testOrderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.findByTestOrderId(bon.getId())).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new User()));
        when(logReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findByTestOrderId(bon.getId())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Report compteRenduCree() {
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("updateStatus")
    class Validation {

        @Test
        @DisplayName("biologie : compte-rendu DRAFT en BIOLOGY, sans texte par défaut ni contenu rédigé")
        void biologie_compteRenduSansTexteParDefaut() {
            TestOrder bio = bonExistant(Discipline.BIOLOGY, analyse("NFS", Discipline.BIOLOGY, 5000));
            bio.setContrat(contrat);
            preparerLaValidation(bio);
            Setting reglages = new Setting();
            reglages.setPlaceholder("<p>Macroscopie : …</p>");
            when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(BRANCH_ID))
                    .thenReturn(Optional.of(reglages));

            service.updateStatus(bio.getId(), "VALIDATED", USER_ID, BRANCH_ID);

            Report cr = compteRenduCree();
            assertThat(cr.getDiscipline()).isEqualTo(Discipline.BIOLOGY);
            assertThat(cr.getStatus()).isEqualTo(ReportStatus.DRAFT);
            assertThat(cr.getCode()).isEqualTo("CO" + bio.getCode());
            assertThat(cr.getDescription()).isNull();
            assertThat(cr.getContent()).isNull();
            assertThat(cr.getContentMicro()).isNull();
            assertThat(cr.getTitleReport()).isNull();
            verifyNoInteractions(settingRepository);
            assertThat(bio.getStatus()).isEqualTo(TestOrderStatus.VALIDATED);
        }

        @Test
        @DisplayName("anatomie pathologique : le texte par défaut reste recopié")
        void pathologie_texteParDefautConserve() {
            TestOrder patho = bonExistant(Discipline.PATHOLOGY, analyse("Biopsie", Discipline.PATHOLOGY, 25000));
            patho.setContrat(contrat);
            preparerLaValidation(patho);
            Setting reglages = new Setting();
            reglages.setPlaceholder("<p>Macroscopie : …</p>");
            when(settingRepository.findFirstByBranchIdOrderByCreatedAtAscIdAsc(BRANCH_ID))
                    .thenReturn(Optional.of(reglages));

            service.updateStatus(patho.getId(), "VALIDATED", USER_ID, BRANCH_ID);

            Report cr = compteRenduCree();
            assertThat(cr.getDiscipline()).isEqualTo(Discipline.PATHOLOGY);
            assertThat(cr.getDescription()).isEqualTo("<p>Macroscopie : …</p>");
        }
    }

    /** Ce que la facturation retient d'un bon : la facture et ses lignes, sans identifiants. */
    private record Facturation(Double subtotal, Double discount, BigDecimal total,
                               String clientName, Contrat contrat, List<String> lignes) {
    }

    /**
     * Parcourt le chemin complet d'un bon : tarif du contrat pour chaque analyse
     * ({@code /test-orders/discount}), création du bon avec ces remises, puis
     * validation. Rend la facture produite.
     */
    private Facturation facturer(Discipline discipline, Invoice factureGroupee) {
        // Mêmes libellés et prix dans les deux disciplines : seules les
        // disciplines diffèrent, les lignes de facture doivent être identiques.
        CategoryTest categorie = new CategoryTest();
        categorie.setId(UUID.randomUUID());
        categorie.setDiscipline(discipline);
        LabTest parAnalyse = analyse("Analyse A", discipline, 10000);
        LabTest parCategorie = analyse("Analyse B", discipline, 4000);
        parCategorie.setCategoryTest(categorie);

        // Règle propre à l'analyse A (montant), règle de catégorie pour B (pourcentage).
        DetailsContrat regleA = new DetailsContrat();
        regleA.setContrat(contrat);
        regleA.setLabTest(parAnalyse);
        regleA.setAmountRemise(BigDecimal.valueOf(2500));
        DetailsContrat regleB = new DetailsContrat();
        regleB.setContrat(contrat);
        regleB.setCategoryTestId(categorie.getId());
        regleB.setPourcentage(BigDecimal.valueOf(10));
        when(detailsContratRepository.findByContratIdAndLabTestId(CONTRAT_ID, parAnalyse.getId()))
                .thenReturn(Optional.of(regleA));
        when(detailsContratRepository.findByContratIdAndLabTestId(CONTRAT_ID, parCategorie.getId()))
                .thenReturn(Optional.empty());
        when(detailsContratRepository.findByContratIdAndCategoryTestId(CONTRAT_ID, categorie.getId()))
                .thenReturn(Optional.of(regleB));

        DiscountDto tarifA = service.getDiscount(CONTRAT_ID, parAnalyse.getId(), BRANCH_ID);
        DiscountDto tarifB = service.getDiscount(CONTRAT_ID, parCategorie.getId(), BRANCH_ID);

        TestOrderRequestDto dto = demande(discipline);
        dto.setContratId(CONTRAT_ID);
        dto.getDetails().add(ligne(parAnalyse, tarifA.discount().doubleValue()));
        dto.getDetails().add(ligne(parCategorie, tarifB.discount().doubleValue()));
        // Le client envoie les totaux qu'il a calculés à partir des remises.
        dto.setSubtotal(14000.0);
        dto.setDiscount(tarifA.discount().add(tarifB.discount()).doubleValue());
        dto.setTotal(14000.0 - dto.getDiscount());

        Mockito.clearInvocations(testOrderRepository, invoiceRepository, invoiceDetailRepository, reportRepository);
        service.create(dto, BRANCH_ID);
        TestOrder bon = bonEnregistre();
        assertThat(bon.getDiscipline()).isEqualTo(discipline);

        preparerLaValidation(bon);
        if (factureGroupee != null) {
            when(invoiceRepository.findFirstByContratIdOrderByCreatedAtAsc(CONTRAT_ID))
                    .thenReturn(Optional.of(factureGroupee));
        }
        service.updateStatus(bon.getId(), "VALIDATED", USER_ID, BRANCH_ID);

        ArgumentCaptor<Invoice> facture = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(facture.capture());
        ArgumentCaptor<InvoiceDetail> lignes = ArgumentCaptor.forClass(InvoiceDetail.class);
        verify(invoiceDetailRepository, Mockito.times(2)).save(lignes.capture());
        Invoice f = facture.getValue();
        List<String> rendu = new ArrayList<>();
        for (InvoiceDetail l : lignes.getAllValues()) {
            assertThat(l.getInvoice()).isSameAs(f);
            rendu.add(l.getLabTest().getName() + "|" + l.getTestName() + "|" + l.getPrice() + "|"
                    + l.getDiscount() + "|" + l.getUnitPrice() + "|" + l.getTotal() + "|" + l.getQuantity());
        }
        // Chaque ligne du bon est marquée facturée.
        assertThat(bon.getDetails()).allSatisfy(d -> assertThat(d.getStatus()).isFalse());
        return new Facturation(f.getSubtotal(), f.getDiscount(), f.getTotal(),
                f.getClientName(), f.getContrat(), rendu);
    }

    @Nested
    @DisplayName("facturation")
    class Facturer {

        @BeforeEach
        void moduleActif() {
            modules.setBiology(true);
        }

        @Test
        @DisplayName("contrat individuel : un bon de biologie produit la même facture qu'un bon d'anatomie pathologique")
        void contratIndividuel_memesLignes() {
            contrat.setInvoiceUnique(false);

            Facturation patho = facturer(Discipline.PATHOLOGY, null);
            Facturation bio = facturer(Discipline.BIOLOGY, null);

            assertThat(bio).isEqualTo(patho);
            // Remise du contrat appliquée : 2500 sur A (montant), 10 % de 4000 sur B.
            assertThat(bio.lignes()).containsExactly(
                    "Analyse A|Analyse A|10000.0|2500.0|10000.0|7500.0|1",
                    "Analyse B|Analyse B|4000.0|400.0|4000.0|3600.0|1");
            assertThat(bio.subtotal()).isEqualTo(14000.0);
            assertThat(bio.discount()).isEqualTo(2900.0);
            assertThat(bio.total()).isEqualByComparingTo("11100");
            assertThat(bio.clientName()).contains("Kora");
            assertThat(bio.contrat()).isSameAs(contrat);
        }

        @Test
        @DisplayName("contrat groupé : un bon de biologie se cumule sur la facture du contrat comme un bon d'anatomie pathologique")
        void contratGroupe_memesLignes() {
            contrat.setInvoiceUnique(true);

            Invoice ouvertePatho = factureOuverte();
            Invoice ouverteBio = factureOuverte();
            Facturation patho = facturer(Discipline.PATHOLOGY, ouvertePatho);
            Facturation bio = facturer(Discipline.BIOLOGY, ouverteBio);

            assertThat(bio).isEqualTo(patho);
            assertThat(bio.lignes()).containsExactly(
                    "Analyse A|Analyse A|10000.0|2500.0|10000.0|7500.0|1",
                    "Analyse B|Analyse B|4000.0|400.0|4000.0|3600.0|1");
            // Cumul sur la facture existante (1000 / 100 / 900).
            assertThat(bio.subtotal()).isEqualTo(15000.0);
            assertThat(bio.discount()).isEqualTo(3000.0);
            assertThat(bio.total()).isEqualByComparingTo("12000");
        }

        private Invoice factureOuverte() {
            Invoice f = new Invoice();
            f.setContrat(contrat);
            f.setClientName("Client du contrat");
            f.setSubtotal(1000.0);
            f.setDiscount(100.0);
            f.setTotal(BigDecimal.valueOf(900));
            f.setPaid(false);
            return f;
        }
    }

    // -------------------------------------------------------------------------
    // Analyses à saisir (B5) : créées à la validation, alignées à la modification
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("analyses à saisir")
    class AnalysesASaisir {

        @Test
        @DisplayName("validation d'un bon de biologie : une analyse à saisir par analyse du bon, auteur transmis")
        @SuppressWarnings("unchecked")
        void validationBiologie_aligne() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs, glycemie);
            bio.setContrat(contrat);
            preparerLaValidation(bio);

            service.updateStatus(bio.getId(), "VALIDATED", USER_ID, BRANCH_ID);

            ArgumentCaptor<Collection<UUID>> analyses = ArgumentCaptor.forClass(Collection.class);
            verify(biologyResultsLifecycle).aligner(eq(bio.getId()), eq(BRANCH_ID), analyses.capture(), eq(USER_ID));
            assertThat(analyses.getValue()).containsExactly(nfs.getId(), glycemie.getId());
        }

        @Test
        @DisplayName("validation d'un bon d'anatomie pathologique : aucune analyse à saisir")
        void validationPathologie_rienASaisir() {
            TestOrder patho = bonExistant(Discipline.PATHOLOGY, analyse("Biopsie", Discipline.PATHOLOGY, 25000));
            patho.setContrat(contrat);
            preparerLaValidation(patho);

            service.updateStatus(patho.getId(), "VALIDATED", USER_ID, BRANCH_ID);

            verifyNoInteractions(biologyResultsLifecycle);
        }

        @Test
        @DisplayName("bon de biologie validé modifié : la nouvelle liste d'analyses est alignée")
        @SuppressWarnings("unchecked")
        void modificationBonValide_aligne() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs);
            bio.setCode("26-0001");
            bio.setStatus(TestOrderStatus.VALIDATED);

            service.update(bio.getId(), demande(null, nfs, glycemie), BRANCH_ID);

            ArgumentCaptor<Collection<UUID>> analyses = ArgumentCaptor.forClass(Collection.class);
            verify(biologyResultsLifecycle).aligner(eq(bio.getId()), eq(BRANCH_ID), analyses.capture(), eq(null));
            assertThat(analyses.getValue()).containsExactly(nfs.getId(), glycemie.getId());
        }

        @Test
        @DisplayName("bon de biologie pas encore validé : rien à aligner (les analyses naîtront à la validation)")
        void modificationBonNonValide_rienAAligner() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs);

            service.update(bio.getId(), demande(null, nfs), BRANCH_ID);

            verifyNoInteractions(biologyResultsLifecycle);
        }

        @Test
        @DisplayName("retrait refusé (analyse saisie) : rien n'est aligné")
        void retraitRefuse_rienAligne() {
            LabTest nfs = analyse("NFS", Discipline.BIOLOGY, 5000);
            LabTest glycemie = analyse("Glycémie", Discipline.BIOLOGY, 2000);
            TestOrder bio = bonExistant(Discipline.BIOLOGY, nfs, glycemie);
            bio.setCode("26-0001");
            when(biologyResultsGuard.analysesAvecResultats(eq(bio.getId()), anyCollection()))
                    .thenReturn(Set.of(glycemie.getId()));

            assertThatThrownBy(() -> service.update(bio.getId(), demande(null, nfs), BRANCH_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(biologyResultsLifecycle);
        }

        @Test
        @DisplayName("bon d'anatomie pathologique validé modifié : jamais aligné")
        void modificationPathologie_jamaisAligne() {
            LabTest biopsie = analyse("Biopsie gastrique", Discipline.PATHOLOGY, 25000);
            TestOrder patho = bonExistant(Discipline.PATHOLOGY, biopsie);
            patho.setCode("26-0002");

            service.update(patho.getId(), demande(null, biopsie), BRANCH_ID);

            verifyNoInteractions(biologyResultsLifecycle);
        }
    }
}

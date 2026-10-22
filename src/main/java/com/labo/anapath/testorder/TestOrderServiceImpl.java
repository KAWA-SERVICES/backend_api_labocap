package com.labo.anapath.testorder;

import com.labo.anapath.common.storage.FichierStockeRepository;
import com.labo.anapath.common.storage.FichierStocke;
import com.labo.anapath.biology.BiologyResultsGuard;
import com.labo.anapath.biology.results.BiologyResultsLifecycle;
import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.NomComplet;

import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.exception.DuplicateResourceException;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.common.module.ModulesProperties;
import com.labo.anapath.client.Client;
import com.labo.anapath.client.ClientRepository;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.contract.DetailsContrat;
import com.labo.anapath.contract.DetailsContratRepository;
import com.labo.anapath.doctor.DoctorRepository;
import com.labo.anapath.doctor.HospitalRepository;
import com.labo.anapath.finance.Invoice;
import com.labo.anapath.finance.InvoiceDetail;
import com.labo.anapath.finance.InvoiceDetailRepository;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.report.LogReport;
import com.labo.anapath.report.LogReportRepository;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportRepository;
import com.labo.anapath.report.ReportStatus;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import com.labo.anapath.setting.SettingRepository;
import com.labo.anapath.test.LabTest;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.test.TypeOrderRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implémentation du service des bons d'examen anatomopathologiques.
 *
 * <p>Orchestre le workflow central du LIS : création des bons, validation (avec génération
 * de code unique, création du compte-rendu DRAFT, journalisation et facturation),
 * mise à jour, suppression et livraison.
 *
 * <p>La protection contre les race conditions lors de la génération du code est assurée
 * par l'interception de {@link org.springframework.dao.DataIntegrityViolationException}
 * sur la contrainte d'unicité du champ {@code code}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestOrderServiceImpl implements TestOrderService {

    private final TestOrderRepository testOrderRepository;
    private final FichierStockeRepository fichiers;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final HospitalRepository hospitalRepository;
    private final ContratRepository contratRepository;
    private final ClientRepository clientRepository;
    private final DetailsContratRepository detailsContratRepository;
    private final TypeOrderRepository typeOrderRepository;
    private final LabTestRepository labTestRepository;
    private final TestOrderMapper testOrderMapper;
    private final ReportRepository reportRepository;
    private final LogReportRepository logReportRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailRepository invoiceDetailRepository;
    private final UserRepository userRepository;
    private final SettingRepository settingRepository;
    private final SettingAppRepository settingAppRepository;
    /** Sert à rendre, dans la liste, le nom de la personne affectée au bon. */
    private final TestOrderAssignmentDetailRepository assignmentDetailRepository;
    private final FileStorageService fileStorageService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    /** Interrupteur du module Biologie : un bon de biologie ne se crée que s'il est actif. */
    private final ModulesProperties modules;
    /** Consulté avant de retirer une analyse d'un bon de biologie (jamais en anatomie pathologique). */
    private final BiologyResultsGuard biologyResultsGuard;
    /** Analyses à saisir d'un bon de biologie : créées à la validation, alignées à la modification. */
    private final BiologyResultsLifecycle biologyResultsLifecycle;

    /** Clé du préfixe de code de bon d'examen, dans {@code setting_apps} (comme Laravel). */
    private static final String PREFIXE_CODE_EXAMEN_KEY = "prefixe_code_demande_examen";

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TestOrderResponseDto> findAll(int page, int size, TestOrderFilterDto filter, UUID branchId) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<TestOrder> orderPage = testOrderRepository
                .findAll(TestOrderSpecification.filter(branchId, filter), pageRequest);

        // Batch fetch reports et invoices pour éviter les N+1
        List<UUID> ids = orderPage.getContent().stream().map(TestOrder::getId).toList();
        Map<UUID, Report> reportMap = reportRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        r -> r.getTestOrder().getId(), r -> r, (a, b) -> a));
        Map<UUID, Invoice> invoiceMap = invoiceRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        i -> i.getTestOrder().getId(), i -> i, (a, b) -> a));

        Map<UUID, String> affectesA = loadAssignedUserNames(ids);

        List<TestOrderResponseDto> content = orderPage.getContent().stream()
                .map(order -> enrichDto(testOrderMapper.toResponseDto(order),
                        reportMap.get(order.getId()), invoiceMap.get(order.getId()),
                        affectesA.get(order.getId())))
                .toList();

        return new PageResponse<>(content, orderPage.getNumber(), orderPage.getSize(),
                orderPage.getTotalElements(), orderPage.getTotalPages(), orderPage.isLast());
    }

    // -------------------------------------------------------------------------
    // Immunohistochimie
    // -------------------------------------------------------------------------

    /**
     * Retourne la page de bons d'examen restreinte aux types immuno
     * ({@code immuno-interne}, {@code immuno-exterme}) pour la branche donnée,
     * en appliquant les critères du filtre standard.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<TestOrderResponseDto> findAllImmuno(int page, int size, TestOrderFilterDto filter, UUID branchId) {
        List<UUID> immunoTypeIds = typeOrderRepository.findImmunoTypeIds(branchId);
        if (immunoTypeIds.isEmpty()) {
            return PageResponse.of(Page.empty());
        }

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Specification<TestOrder> spec = TestOrderSpecification.filter(branchId, filter)
                .and(TestOrderSpecification.typeOrderIdIn(immunoTypeIds));

        Page<TestOrder> orderPage = testOrderRepository.findAll(spec, pageRequest);

        // Batch fetch reports et invoices pour éviter les N+1
        List<UUID> ids = orderPage.getContent().stream().map(TestOrder::getId).toList();
        Map<UUID, Report> reportMap = reportRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        r -> r.getTestOrder().getId(), r -> r, (a, b) -> a));
        Map<UUID, Invoice> invoiceMap = invoiceRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        i -> i.getTestOrder().getId(), i -> i, (a, b) -> a));

        Map<UUID, String> affectesA = loadAssignedUserNames(ids);

        List<TestOrderResponseDto> content = orderPage.getContent().stream()
                .map(order -> enrichDto(testOrderMapper.toResponseDto(order),
                        reportMap.get(order.getId()), invoiceMap.get(order.getId()),
                        affectesA.get(order.getId())))
                .toList();

        return new PageResponse<>(content, orderPage.getNumber(), orderPage.getSize(),
                orderPage.getTotalElements(), orderPage.getTotalPages(), orderPage.isLast());
    }

    /**
     * Compte les bons immuno en attente de compte rendu — reprise du helper Laravel
     * {@code getnbrTestOrderImmunopending()} (compte rendu existant, en statut
     * {@code DRAFT} ou {@code PENDING_REVIEW}).
     */
    @Override
    @Transactional(readOnly = true)
    public long countImmunoPending(UUID branchId) {
        List<UUID> immunoTypeIds = typeOrderRepository.findImmunoTypeIds(branchId);
        if (immunoTypeIds.isEmpty()) return 0L;
        return testOrderRepository.countPendingReportByTypeIds(branchId, immunoTypeIds);
    }

    /**
     * Compte les bons de cytologie/histologie en attente de compte rendu — reprise du
     * helper Laravel {@code getnbrTestOrderpending()} qui alimente le badge du menu
     * « Demandes d'examen ».
     */
    @Override
    @Transactional(readOnly = true)
    public long countPending(UUID branchId) {
        List<UUID> typeIds = typeOrderRepository.findCytoHistoTypeIds(branchId);
        if (typeIds.isEmpty()) return 0L;
        return testOrderRepository.countPendingReportByTypeIds(branchId, typeIds);
    }

    @Override
    @Transactional(readOnly = true)
    public TestOrderResponseDto findById(UUID id, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        TestOrderResponseDto dto = testOrderMapper.toResponseDto(order);
        Report report = reportRepository.findByTestOrderId(order.getId()).orElse(null);
        Invoice invoice = invoiceRepository.findByTestOrderId(order.getId()).orElse(null);
        // `List.of` refuse les valeurs nulles : on ne consulte les affectations que
        // si le bon porte un identifiant. Le cas ne se rencontre pas sur une
        // entité chargée depuis la base, mais un bon d'examen sans identifiant ne
        // peut de toute façon figurer dans aucune affectation.
        String affecteA = order.getId() == null
                ? null
                : loadAssignedUserNames(List.of(order.getId())).get(order.getId());
        return enrichDto(dto, report, invoice, affecteA);
    }

    /**
     * Crée un nouveau bon d'examen au statut PENDING.
     *
     * <p>Calcule automatiquement le total de chaque ligne d'analyse :
     * {@code total = prix - (prix × remise / 100)}.
     *
     * @param dto      données saisies par l'utilisateur
     * @param branchId branche du technicien connecté
     * @return le bon persisté avec ses détails
     */
    @Override
    @Transactional
    public TestOrderResponseDto create(TestOrderRequestDto dto, UUID branchId) {
        // Sans discipline, le client est un client d'anatomie pathologique (web
        // actuel, mobile) : il crée ce qu'il a toujours créé.
        Discipline discipline = dto.getDiscipline() != null ? dto.getDiscipline() : Discipline.PATHOLOGY;
        if (discipline == Discipline.BIOLOGY) {
            exigerLeModuleBiologie();
            refuserLeTypeDeBonEnBiologie(dto);
        }
        assertNoDuplicateTests(dto.getDetails());
        assertContractQuotaAvailable(dto.getContratId(), branchId);

        TestOrder order = new TestOrder();
        order.setBranchId(branchId);
        order.setCode(null);
        order.setStatus(TestOrderStatus.PENDING);
        order.setDiscipline(discipline);
        order.setPrelevementDate(dto.getPrelevementDate());
        order.setReferenceHopital(dto.getReferenceHopital());
        order.setIsUrgent(dto.getIsUrgent() != null ? dto.getIsUrgent() : false);
        order.setOption(dto.getOption());
        poserQuiEstFacture(order, dto);
        order.setTestAffiliate(dto.getTestAffiliate());
        order.setSubtotal(dto.getSubtotal());
        order.setDiscount(dto.getDiscount());
        order.setTotal(dto.getTotal());
        if (dto.getAssignedToUserId() != null) {
            order.setAssignedToUserId(dto.getAssignedToUserId());
        }

        order.setPatient(patientRepository.findByIdAndBranchId(dto.getPatientId(), branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", dto.getPatientId())));

        if (dto.getDoctorId() != null) {
            order.setDoctor(doctorRepository.findByIdAndBranchId(dto.getDoctorId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Médecin", dto.getDoctorId())));
        }
        if (dto.getHospitalId() != null) {
            order.setHospital(hospitalRepository.findByIdAndBranchId(dto.getHospitalId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hôpital", dto.getHospitalId())));
        }
        if (dto.getContratId() != null) {
            order.setContrat(contratRepository.findByIdAndBranchId(dto.getContratId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Contrat", dto.getContratId())));
        }
        if (dto.getTypeOrderId() != null) {
            order.setTypeOrder(typeOrderRepository.findByIdAndBranchId(dto.getTypeOrderId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Type de bon", dto.getTypeOrderId())));
        }

        if (dto.getDetails() != null && !dto.getDetails().isEmpty()) {
            List<DetailTestOrder> details = dto.getDetails().stream().map(detailDto -> {
                LabTest labTest = labTestRepository.findByIdAndBranchId(detailDto.getLabTestId(), branchId)
                        .orElseThrow(() -> new ResourceNotFoundException("Analyse", detailDto.getLabTestId()));
                exigerLaDisciplineDuBon(labTest, order.getDiscipline());
                DetailTestOrder detail = new DetailTestOrder();
                detail.setTestOrder(order);
                detail.setLabTest(labTest);
                detail.setTestName(labTest.getName() != null ? labTest.getName() : "");
                double price = labTest.getPrice() != null ? labTest.getPrice().doubleValue() : 0.0;
                double disc = detailDto.getDiscount() != null ? detailDto.getDiscount() : 0.0;
                detail.setPrice(price);
                detail.setDiscount(disc);
                // La remise (disc) est un MONTANT (amountRemise du contrat), pas un
                // pourcentage : total = prix - remise (borné à 0), cohérent avec le
                // calcul du frontend. L'ancienne formule prix - prix*disc/100 donnait
                // des totaux aberrants (ex. remise 5000 sur 25000 → -1 225 000).
                detail.setTotal(Math.max(0.0, price - disc));
                return detail;
            }).toList();
            order.getDetails().addAll(details);
        }

        TestOrder saved = testOrderRepository.save(order);
        log.info("Bon d'examen créé: id={}, patient={}", saved.getId(), saved.getPatient().getId());
        return testOrderMapper.toResponseDto(saved);
    }

    /**
     * Met à jour un bon d'examen existant. Interdit dès que la facture est payée.
     *
     * <p>La garde portait auparavant sur le statut VALIDATED, ce qui n'a pas
     * d'équivalent dans Laravel : {@code TestOrderController@updateTest} n'y
     * vérifie que {@code $invoice->paid != 1}. La contrainte d'origine est
     * comptable — ne pas désaligner une facture encaissée de ses lignes — et
     * non éditoriale.</p>
     *
     * <p>Elle était en outre incohérente : un bon VALIDATED était refusé
     * tandis qu'un bon DELIVERED, plus avancé, passait. En recette, des
     * dossiers déjà sortis mais nécessitant un complément se retrouvaient
     * bloqués sans raison comptable.</p>
     *
     * @param id       identifiant UUID du bon
     * @param dto      nouvelles données
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return le bon mis à jour
     */
    @Override
    @Transactional
    public TestOrderResponseDto update(UUID id, TestOrderRequestDto dto, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        // Absence de facture = rien à désaligner : la modification reste ouverte,
        // comme dans Laravel où `$invoice->paid` d'une facture absente n'est
        // jamais égal à 1.
        Invoice factureLiee = invoiceRepository.findByTestOrderId(order.getId()).orElse(null);
        if (factureLiee != null && Boolean.TRUE.equals(factureLiee.getPaid())) {
            throw new InvalidOperationException(
                    "Facture déjà payée : les examens de ce bon ne peuvent plus être modifiés.");
        }
        // La colonne n'est pas modifiable (updatable = false) : sans ce refus, une
        // autre discipline serait acceptée puis ignorée en silence.
        if (dto.getDiscipline() != null && dto.getDiscipline() != order.getDiscipline()) {
            throw new BusinessException("La discipline d'un bon d'examen ne se modifie pas après sa création.");
        }
        if (order.getDiscipline() == Discipline.BIOLOGY) {
            refuserLeTypeDeBonEnBiologie(dto);
        }
        assertNoDuplicateTests(dto.getDetails());
        order.setPrelevementDate(dto.getPrelevementDate());
        order.setReferenceHopital(dto.getReferenceHopital());
        if (dto.getIsUrgent() != null) order.setIsUrgent(dto.getIsUrgent());
        if (dto.getOption() != null) order.setOption(dto.getOption());
        poserQuiEstFacture(order, dto);
        if (dto.getTestAffiliate() != null) order.setTestAffiliate(dto.getTestAffiliate());
        // « Affecter à » (Laravel : attribuate_doctor_id) = docteur signataire.
        // On aligne attribuateDoctorId ET assignedToUserId sur le même utilisateur
        // pour que « Mon espace » et les stats d'assignation restent cohérents.
        if (dto.getAssignedToUserId() != null) {
            order.setAssignedToUserId(dto.getAssignedToUserId());
            order.setAttribuateDoctorId(dto.getAssignedToUserId());
        }

        if (dto.getDoctorId() != null) {
            order.setDoctor(doctorRepository.findByIdAndBranchId(dto.getDoctorId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Médecin", dto.getDoctorId())));
        }
        if (dto.getHospitalId() != null) {
            order.setHospital(hospitalRepository.findByIdAndBranchId(dto.getHospitalId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hôpital", dto.getHospitalId())));
        }
        if (dto.getContratId() != null) {
            order.setContrat(contratRepository.findByIdAndBranchId(dto.getContratId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Contrat", dto.getContratId())));
        }
        if (dto.getTypeOrderId() != null) {
            order.setTypeOrder(typeOrderRepository.findByIdAndBranchId(dto.getTypeOrderId(), branchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Type de bon", dto.getTypeOrderId())));
        }

        // Remplacement des analyses (details) : le front envoie la liste complète
        // voulue à chaque ajout/modif/suppression. orphanRemoval supprime les
        // anciennes lignes, le cascade persiste les nouvelles.
        if (dto.getDetails() != null) {
            if (order.getDiscipline() == Discipline.BIOLOGY) {
                refuserLeRetraitDAnalysesAvecResultats(order, dto.getDetails());
            }
            order.getDetails().clear();
            List<DetailTestOrder> details = dto.getDetails().stream().map(detailDto -> {
                LabTest labTest = labTestRepository.findByIdAndBranchId(detailDto.getLabTestId(), branchId)
                        .orElseThrow(() -> new ResourceNotFoundException("Analyse", detailDto.getLabTestId()));
                exigerLaDisciplineDuBon(labTest, order.getDiscipline());
                DetailTestOrder detail = new DetailTestOrder();
                detail.setTestOrder(order);
                detail.setLabTest(labTest);
                detail.setTestName(labTest.getName() != null ? labTest.getName() : "");
                double price = labTest.getPrice() != null ? labTest.getPrice().doubleValue() : 0.0;
                double disc = detailDto.getDiscount() != null ? detailDto.getDiscount() : 0.0;
                detail.setPrice(price);
                detail.setDiscount(disc);
                // La remise (disc) est un MONTANT (amountRemise du contrat), pas un
                // pourcentage : total = prix - remise (borné à 0), cohérent avec le
                // calcul du frontend. L'ancienne formule prix - prix*disc/100 donnait
                // des totaux aberrants (ex. remise 5000 sur 25000 → -1 225 000).
                detail.setTotal(Math.max(0.0, price - disc));
                return detail;
            }).toList();
            order.getDetails().addAll(details);
            double subtotal = details.stream().mapToDouble(DetailTestOrder::getPrice).sum();
            double total = details.stream().mapToDouble(DetailTestOrder::getTotal).sum();
            order.setSubtotal(subtotal);
            order.setTotal(total);
            order.setDiscount(subtotal - total);
        }

        TestOrderResponseDto reponse = testOrderMapper.toResponseDto(testOrderRepository.save(order));
        // Bon de biologie déjà validé (il a son code) : une analyse ajoutée s'ouvre à
        // la saisie, une analyse retirée — forcément sans résultat, vérifié plus
        // haut — quitte la liste de travail.
        if (dto.getDetails() != null && order.getDiscipline() == Discipline.BIOLOGY && order.getCode() != null) {
            biologyResultsLifecycle.aligner(order.getId(), branchId, analysesDuBon(order), null);
        }
        return reponse;
    }

    /**
     * Supprime (soft delete) un bon d'examen non encore validé.
     * Un bon validé ne peut pas être supprimé pour préserver la traçabilité.
     *
     * @param id       identifiant UUID du bon
     * @param branchId identifiant de la branche (isolation multi-tenant)
     */
    @Override
    @Transactional
    public void delete(UUID id, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        if (order.getStatus() == TestOrderStatus.VALIDATED) {
            throw new InvalidOperationException("Impossible de supprimer un bon d'examen déjà validé.");
        }
        testOrderRepository.delete(order);
        log.info("Bon d'examen supprimé (soft): {}", id);
    }

    /**
     * Déclenche le workflow de validation du bon d'examen (passage au statut VALIDATED).
     *
     * <p>Séquence complète :
     * <ol>
     *   <li>Seul le statut {@code VALIDATED} est accepté par cet endpoint</li>
     *   <li>Récupération du bon filtrée par branchId (sécurité multi-tenant)</li>
     *   <li>Vérification de la présence du contrat (obligatoire)</li>
     *   <li>Génération du code unique si le bon n'en possède pas encore ;
     *       une {@link org.springframework.dao.DataIntegrityViolationException} est traduite
     *       en HTTP 409 pour protéger contre les race conditions concurrentes</li>
     *   <li>Création ou mise à jour du {@link Report} associé (code = "CO" + code du bon)</li>
     *   <li>Journalisation de l'action dans {@link LogReport}</li>
     *   <li>Facturation : individuelle si {@code invoice_unique=false}, groupée sinon</li>
     * </ol>
     *
     * @param id       identifiant UUID du bon
     * @param status   valeur attendue : {@code "VALIDATED"}
     * @param userId   identifiant de l'utilisateur effectuant la validation (pour le log)
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return le bon validé avec son code généré
     */
    @Override
    @Transactional
    public TestOrderResponseDto updateStatus(UUID id, String status, UUID userId, UUID branchId) {
        // AC3: seul VALIDATED est supporté via cet endpoint
        if (!"VALIDATED".equalsIgnoreCase(status)) {
            throw new BusinessException("Seul le statut VALIDATED est géré par cet endpoint");
        }

        // AC4: bon doit exister, filtré par branchId (sécurité multi-tenant)
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));

        // AC10: contrat obligatoire
        if (order.getContrat() == null) {
            throw new BusinessException("TEST_ORDER_NO_CONTRACT");
        }

        // AC1/AC2: génération code uniquement si pas encore validé
        if (order.getCode() == null) {
            String code = generateCodeExamen(branchId);
            order.setCode(code);
            order.setStatus(TestOrderStatus.VALIDATED);
            try {
                testOrderRepository.saveAndFlush(order);
            } catch (DataIntegrityViolationException e) {
                // AC9: race condition — collision sur le code unique → HTTP 409
                log.warn("Race condition lors de la validation du bon {}: code={}", id, code);
                throw new DuplicateResourceException("CODE_GENERATION_CONFLICT");
            }
        }

        // AC5: Report — création ou mise à jour du code
        Report report = reportRepository.findByTestOrderId(order.getId()).orElse(null);
        if (report == null) {
            report = new Report();
            report.setBranchId(branchId);
            report.setTestOrder(order);
            // Le compte-rendu hérite de la discipline du bon : plusieurs
            // requêtes filtrent sur reports sans joindre test_orders, et la
            // colonne n'est plus modifiable après l'insertion.
            report.setDiscipline(order.getDiscipline());
            report.setStatus(ReportStatus.DRAFT);
            // Le texte par défaut est un gabarit de compte-rendu rédigé : il n'a pas
            // de sens en biologie, dont les résultats vivent dans leurs propres
            // tables. Les champs rédactionnels du compte-rendu y restent nuls.
            if (order.getDiscipline() != Discipline.BIOLOGY) {
                // Texte par défaut du compte rendu : Laravel lit `Setting::first()->placeholder`,
                // le singleton de la table `settings`. L'ancienne lecture cherchait la clé
                // `prefixe_code_demande_examen`, qui appartient à `setting_apps` et n'existe pas
                // dans `settings` : le placeholder était donc toujours vide.
                String placeholder = settingRepository
                        .findFirstByBranchIdOrderByCreatedAtAscIdAsc(branchId)
                        .map(s -> s.getPlaceholder() != null ? s.getPlaceholder() : "")
                        .orElse("");
                report.setDescription(placeholder);
            }
        }
        report.setCode("CO" + order.getCode());
        report = reportRepository.save(report);

        // AC6: LogReport
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur", userId));
        LogReport logReport = new LogReport();
        logReport.setBranchId(branchId);
        logReport.setReport(report);
        logReport.setUser(user);
        logReport.setAction("Créer un nouveau report");
        logReportRepository.save(logReport);
        log.info("Report créé/mis à jour pour bon {}: code={}", id, report.getCode());

        // Biologie : une analyse à saisir (PENDING) par analyse du bon. Rejouable —
        // une seconde validation ne crée rien de plus.
        if (order.getDiscipline() == Discipline.BIOLOGY) {
            biologyResultsLifecycle.aligner(order.getId(), branchId, analysesDuBon(order), userId);
        }

        // AC7/AC8: Facturation
        boolean invoiceGrouped = Boolean.TRUE.equals(order.getContrat().getInvoiceUnique());
        if (!invoiceGrouped) {
            processIndividualInvoice(order, branchId);
        } else {
            processGroupedInvoice(order);
        }

        return testOrderMapper.toResponseDto(
                testOrderRepository.findByIdAndBranchId(id, branchId).orElseThrow());
    }

    /**
     * Enrichit un {@link TestOrderResponseDto} avec les informations du rapport et de la facture associés.
     *
     * @param dto     DTO de base issu du mapper
     * @param report  rapport associé au bon (null si pas encore créé)
     * @param invoice facture associée au bon (null si pas encore créée)
     * @return un nouveau DTO avec les champs reportId, reportStatus, reportIsDelivered, invoiceId renseignés
     */
    /**
     * Noms des personnes affectées, indexés par bon d'examen.
     *
     * <p>Une requête pour toute la page, comme pour les comptes rendus et les
     * factures juste au-dessus. Rendre une correspondance vide sur une liste
     * vide évite un {@code IN ()} que PostgreSQL refuse.</p>
     */
    private Map<UUID, String> loadAssignedUserNames(List<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        Map<UUID, String> noms = new java.util.HashMap<>();
        for (Object[] ligne : assignmentDetailRepository.findAssignedUserNames(orderIds)) {
            String nom = ligne[1] != null ? ligne[1].toString().trim() : "";
            if (!nom.isEmpty()) noms.put((UUID) ligne[0], nom);
        }
        return noms;
    }

    /**
     * Qui paiera cet examen : le patient, ou l'établissement nommé.
     *
     * <p>Les trois champs vont ensemble. Un nom vide efface les deux autres :
     * revenir au patient doit être un geste, pas un oubli — sans cela, effacer
     * le seul nom laisserait une adresse et un IFU orphelins sur la facture
     * suivante.</p>
     */
    private void poserQuiEstFacture(TestOrder order, TestOrderRequestDto dto) {
        String nom = dto.getFactureANom();
        if (nom == null || nom.isBlank()) {
            order.setFactureANom(null);
            order.setFactureAAdresse(null);
            order.setFactureAIfu(null);
            return;
        }
        order.setFactureANom(nom.trim());
        order.setFactureAAdresse(dto.getFactureAAdresse() == null
                ? null : dto.getFactureAAdresse().trim());
        order.setFactureAIfu(dto.getFactureAIfu() == null || dto.getFactureAIfu().isBlank()
                ? null : dto.getFactureAIfu().trim());
    }

    private TestOrderResponseDto enrichDto(TestOrderResponseDto dto, Report report, Invoice invoice,
                                          String assignedUserName) {
        return new TestOrderResponseDto(
                dto.id(), dto.code(), dto.status(), dto.prelevementDate(),
                dto.referenceHopital(), dto.isUrgent(), dto.subtotal(), dto.discount(), dto.total(),
                dto.patientId(), dto.patientFirstname(), dto.patientLastname(),
                dto.doctorId(), dto.doctorName(), dto.hospitalId(), dto.hospitalName(),
                dto.contratId(), dto.contratName(), dto.typeOrderId(), dto.typeOrderTitle(),
                dto.attribuateDoctorId(), dto.assignedToUserId(),
                dto.details(), dto.branchId(), dto.createdAt(),
                // champs enrichis :
                report != null ? report.getId() : null,
                report != null ? report.getStatus().name() : null,
                report != null && report.isDelivered(),
                invoice != null ? invoice.getId() : null,
                dto.archive(),
                dto.testAffiliate(),
                dto.option(),
                dto.factureANom(), dto.factureAAdresse(), dto.factureAIfu(),
                assignedUserName,
                dto.discipline()
        );
    }

    /**
     * Traite la facturation individuelle pour un contrat avec {@code invoice_unique=false}.
     *
     * <p>Crée une nouvelle facture dédiée à ce bon d'examen si elle n'existe pas encore,
     * sinon met à jour les montants de la facture existante.
     * Dans tous les cas, ajoute les lignes de détail non encore facturées.
     *
     * @param order    le bon d'examen à facturer
     * @param branchId identifiant de la branche (nécessaire pour la création de la facture)
     */
    // AC7: contrat individuel (invoice_unique=false)
    private void processIndividualInvoice(TestOrder order, UUID branchId) {
        Invoice invoice = invoiceRepository.findByTestOrderId(order.getId()).orElse(null);
        if (invoice == null) {
            invoice = new Invoice();
            invoice.setBranchId(branchId);
            invoice.setDate(LocalDate.now()); // calque Laravel : 'date' => Carbon::now()
            invoice.setTestOrder(order);
            invoice.setPatient(order.getPatient());
            invoice.setContrat(order.getContrat());
            poserLIdentiteDeFacturation(invoice, order);
            invoice.setSubtotal(order.getSubtotal());
            invoice.setDiscount(order.getDiscount());
            invoice.setTotal(order.getTotal() != null
                    ? BigDecimal.valueOf(order.getTotal()) : BigDecimal.ZERO);
            invoice.setCode(generateCodeFacture(branchId));
            invoice = invoiceRepository.save(invoice);
        } else {
            // Laravel réécrit aussi l'identité du patient sur la facture existante.
            invoice.setPatient(order.getPatient());
            poserLIdentiteDeFacturation(invoice, order);
            invoice.setSubtotal(order.getSubtotal());
            invoice.setDiscount(order.getDiscount());
            invoice.setTotal(order.getTotal() != null
                    ? BigDecimal.valueOf(order.getTotal()) : BigDecimal.ZERO);
            invoiceRepository.save(invoice);
        }
        addInvoiceDetails(invoice, order.getDetails());
    }

    /**
     * Qui la facture nomme, et à quelle adresse.
     *
     * <p>L'établissement désigné sur la demande s'il y en a un, le patient
     * sinon. Le laboratoire soigne quelqu'un et facture parfois quelqu'un
     * d'autre — une clinique qui adresse son patient et règle l'examen — et
     * ces deux identités n'ont aucune raison de coïncider.</p>
     *
     * <p>Une identité saisie à la main n'est jamais réécrite. Cette méthode est
     * appelée à chaque validation du bon, et sans cette garde une adresse de
     * facturation choisie au moment de déclarer disparaîtrait au geste
     * suivant, en silence.</p>
     */
    private void poserLIdentiteDeFacturation(Invoice invoice, TestOrder order) {
        if (invoice.isFacturationFigee()) {
            return;
        }
        if (order.estFactureAUnTiers()) {
            invoice.setClientName(order.getFactureANom());
            invoice.setClientAddress(order.getFactureAAdresse());
            invoice.setClientIfu(order.getFactureAIfu());
            return;
        }
        // « Prénom Nom », et non l'inverse : même ordre que
        // `FinanceMapper.patientName`, sans quoi le bandeau « informations à
        // actualiser » s'affiche dès la création de la facture alors que rien
        // n'a changé, et « Actualiser » réécrit la même valeur sans jamais le
        // faire disparaître. Corrigé sur main pendant que cette branche
        // vivait ; la reprendre telle quelle aurait réintroduit le défaut.
        invoice.setClientName(NomComplet.de(
                order.getPatient().getFirstname(), order.getPatient().getLastname()));
        invoice.setClientAddress(order.getPatient().getAdresse());
        // Un patient n'a pas d'IFU : le laisser d'une facturation précédente
        // ferait déclarer cette vente au nom d'un établissement qu'elle ne
        // concerne plus.
        invoice.setClientIfu(null);
    }

    /**
     * Traite la facturation groupée pour un contrat avec {@code invoice_unique=true}.
     *
     * <p>Récupère la facture ouverte la plus récente du contrat et cumule les montants du bon.
     * Lève une {@link com.labo.anapath.common.exception.BusinessException} si aucune facture
     * ouverte n'existe ({@code CONTRACT_NO_INVOICE}) ou si elle est déjà payée
     * ({@code CONTRACT_INVOICE_ALREADY_PAID}).
     *
     * @param order le bon d'examen dont les montants sont à cumuler sur la facture groupée
     */
    // AC8: contrat groupé (invoice_unique=true) — équivalent de ->where('contrat_id', id)->first() Laravel
    private void processGroupedInvoice(TestOrder order) {
        // Laravel cible `->where('contrat_id', $id)->first()`, soit la facture la PLUS
        // ANCIENNE du contrat. Cibler la plus récente bloquait la validation dès qu'une
        // facture postérieure était payée, alors que la facture groupée du contrat était
        // encore ouverte.
        Invoice invoice = invoiceRepository
                .findFirstByContratIdOrderByCreatedAtAsc(order.getContrat().getId())
                .orElse(null);
        if (invoice == null) {
            throw new BusinessException("CONTRACT_NO_INVOICE");
        }
        if (Boolean.TRUE.equals(invoice.getPaid())) {
            throw new BusinessException("CONTRACT_INVOICE_ALREADY_PAID");
        }
        // Laravel réécrit l'identité du client à chaque cumul, depuis le client du contrat.
        Client client = order.getContrat().getClientId() != null
                ? clientRepository.findById(order.getContrat().getClientId()).orElse(null)
                : null;
        if (client != null && !invoice.isFacturationFigee()) {
            invoice.setClientName(client.getName());
            invoice.setClientAddress(client.getAdress());
            // Le client d'un contrat porte un IFU en base ; il n'arrivait
            // jamais jusqu'à la déclaration.
            invoice.setClientIfu(client.getIfu());
        }
        double newSubtotal = (invoice.getSubtotal() != null ? invoice.getSubtotal() : 0.0)
                + (order.getSubtotal() != null ? order.getSubtotal() : 0.0);
        double newDiscount = (invoice.getDiscount() != null ? invoice.getDiscount() : 0.0)
                + (order.getDiscount() != null ? order.getDiscount() : 0.0);
        BigDecimal currentTotal = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;
        BigDecimal orderTotal = order.getTotal() != null
                ? BigDecimal.valueOf(order.getTotal()) : BigDecimal.ZERO;
        invoice.setSubtotal(newSubtotal);
        invoice.setDiscount(newDiscount);
        invoice.setTotal(currentTotal.add(orderTotal));
        invoiceRepository.save(invoice);
        addInvoiceDetails(invoice, order.getDetails());
    }

    /**
     * Ajoute les lignes de détail non encore facturées à une facture.
     *
     * <p>Un détail est considéré "non facturé" si son champ {@code status} vaut {@code null}
     * ou {@code true}. Une fois traité, il est marqué {@code status=false} pour éviter
     * une double facturation lors d'un rechargement de la transaction.
     *
     * @param invoice la facture cible
     * @param details la liste des détails du bon d'examen
     */
    // Crée les InvoiceDetail pour les détails non encore facturés (status != false)
    /**
     * Refuse une liste d'analyses contenant deux fois la même analyse.
     *
     * <p>Calque du garde-fou Laravel {@code TestOrderController::details_store()}, qui teste
     * {@code $test_order->details()->whereTestId($id)->exists()} avant d'insérer et renvoie
     * « Examin deja ajouté ». Côté Next le front renvoie la liste complète des analyses à
     * chaque ajout via {@code PUT /test-orders/{id}} : sans ce contrôle la même analyse peut
     * être ajoutée deux fois, puis facturée deux fois à la validation du bon.
     *
     * @param details analyses demandées (peut être null ou vide)
     * @throws BusinessException si un {@code labTestId} apparaît plus d'une fois
     */
    /**
     * N'autorise que des images JPEG ou PNG parmi les fichiers téléversés.
     *
     * <p>Calque de la seule règle de validation de fichier de Laravel, dans
     * {@code TestOrderController::createimagegallerie()} :
     * {@code 'files_name.*' => 'file|mimes:jpg,png'}. La règle {@code mimes} de Laravel
     * fusionne explicitement {@code jpg} et {@code jpeg}, et surtout elle contrôle le
     * <em>contenu réel</em> du fichier ({@code guessExtension()} s'appuie sur les octets,
     * pas sur le nom) : renommer un exécutable en {@code .png} ne passe pas.
     *
     * <p>On reproduit ce contrôle en lisant la signature du fichier plutôt que son
     * extension ou son {@code Content-Type}, tous deux fournis par le client donc
     * falsifiables.
     *
     * <p>Portée volontairement limitée à cet endpoint : Laravel ne valide aucun autre
     * upload (pièce jointe de demande, documents, photos d'employé, justificatifs…).
     *
     * @param files fichiers reçus
     * @throws BusinessException si l'un des fichiers n'est ni JPEG ni PNG
     */
    private void assertJpegOrPng(List<org.springframework.web.multipart.MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException("Aucun fichier reçu.");
        }
        for (org.springframework.web.multipart.MultipartFile file : files) {
            if (file.isEmpty() || !isJpegOrPng(file)) {
                String name = file.getOriginalFilename() != null
                        ? file.getOriginalFilename() : "fichier";
                throw new BusinessException(
                        "« " + name + " » n'est pas une image JPG ou PNG. "
                                + "Seuls ces deux formats sont acceptés.");
            }
        }
    }

    /** Signature JPEG ({@code FF D8 FF}) ou PNG ({@code 89 50 4E 47 0D 0A 1A 0A}). */
    private boolean isJpegOrPng(org.springframework.web.multipart.MultipartFile file) {
        byte[] head = new byte[8];
        try (java.io.InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, 8);
            if (read < 3) return false;
            boolean jpeg = (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF;
            boolean png = read >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                    && (head[4] & 0xFF) == 0x0D && (head[5] & 0xFF) == 0x0A
                    && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0x0A;
            return jpeg || png;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Refuse un bon de biologie quand le module n'est pas activé sur ce déploiement.
     *
     * <p>{@code /test-orders} est commun aux deux disciplines : l'intercepteur du
     * module ne le couvre pas, c'est donc ici que la biologie se ferme.</p>
     */
    private void exigerLeModuleBiologie() {
        if (!modules.isBiology()) {
            throw new BusinessException("Le module Biologie n'est pas activé sur ce laboratoire.");
        }
    }

    /**
     * Refuse un type de bon sur un bon de biologie.
     *
     * <p>Les types (biopsie, cytologie, immuno…) sont propres à l'anatomie
     * pathologique, et les compteurs qui s'appuient sur eux — bons immuno, bons
     * cyto/histo en attente — ne filtrent pas la discipline : un bon de biologie
     * typé y serait compté.</p>
     */
    private static void refuserLeTypeDeBonEnBiologie(TestOrderRequestDto dto) {
        if (dto.getTypeOrderId() != null) {
            throw new BusinessException("Un bon de biologie ne porte pas de type de bon : "
                    + "les types (biopsie, cytologie, immuno…) relèvent de l'anatomie pathologique.");
        }
    }

    /**
     * Refuse une analyse d'une autre discipline que celle du bon : un bon ne mélange
     * pas les disciplines, sans quoi une analyse de biologie suivrait le circuit de
     * macroscopie et de compte-rendu rédigé, ou l'inverse.
     */
    private static void exigerLaDisciplineDuBon(LabTest labTest, Discipline disciplineDuBon) {
        Discipline duBon = disciplineDuBon != null ? disciplineDuBon : Discipline.PATHOLOGY;
        Discipline deLAnalyse = labTest.getDiscipline() != null ? labTest.getDiscipline() : Discipline.PATHOLOGY;
        if (deLAnalyse != duBon) {
            throw new BusinessException("Un bon ne mélange pas les disciplines : l'analyse « "
                    + labTest.getName() + " » relève de " + libelle(deLAnalyse)
                    + ", ce bon de " + libelle(duBon) + ".");
        }
    }

    private static String libelle(Discipline discipline) {
        return discipline == Discipline.BIOLOGY ? "la biologie" : "l'anatomie pathologique";
    }

    /**
     * Refuse de retirer d'un bon de biologie une analyse qui porte déjà des résultats.
     *
     * <p>La modification remplace toutes les lignes du bon ; une analyse absente de la
     * nouvelle liste est une analyse retirée. Les résultats étant rattachés au couple
     * (bon, analyse), la retirer les rendrait orphelins.</p>
     *
     * @param order     bon de biologie, avec ses lignes actuelles
     * @param demandees nouvelle liste complète des analyses
     * @throws BusinessException si une analyse retirée porte des résultats
     */
    private void refuserLeRetraitDAnalysesAvecResultats(TestOrder order,
                                                        List<DetailTestOrderRequestDto> demandees) {
        Set<UUID> conservees = demandees.stream()
                .map(DetailTestOrderRequestDto::getLabTestId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> retirees = new LinkedHashMap<>();
        for (DetailTestOrder detail : order.getDetails()) {
            // Analyse supprimée du catalogue (@NotFound IGNORE) : plus d'identifiant
            // à rapprocher, donc rien à protéger ici.
            if (detail.getLabTest() == null || detail.getLabTest().getId() == null) continue;
            UUID labTestId = detail.getLabTest().getId();
            if (!conservees.contains(labTestId)) {
                retirees.put(labTestId, detail.getTestName());
            }
        }
        if (retirees.isEmpty()) return;

        Set<UUID> avecResultats = biologyResultsGuard.analysesAvecResultats(order.getId(), retirees.keySet());
        if (avecResultats == null || avecResultats.isEmpty()) return;
        List<String> noms = retirees.entrySet().stream()
                .filter(e -> avecResultats.contains(e.getKey()))
                .map(e -> "« " + e.getValue() + " »")
                .toList();
        throw new BusinessException(noms.size() == 1
                ? "Des résultats sont déjà saisis pour l'analyse " + noms.get(0)
                        + " : elle ne peut plus être retirée du bon."
                : "Des résultats sont déjà saisis pour les analyses " + String.join(", ", noms)
                        + " : elles ne peuvent plus être retirées du bon.");
    }

    /** Analyses (catalogue) portées par les lignes du bon ; une analyse supprimée du catalogue est ignorée. */
    private static List<UUID> analysesDuBon(TestOrder order) {
        return order.getDetails().stream()
                .map(DetailTestOrder::getLabTest)
                .filter(java.util.Objects::nonNull)
                .map(LabTest::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private void assertNoDuplicateTests(List<DetailTestOrderRequestDto> details) {
        if (details == null || details.isEmpty()) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (DetailTestOrderRequestDto detail : details) {
            UUID labTestId = detail.getLabTestId();
            if (labTestId != null && !seen.add(labTestId)) {
                throw new BusinessException("Cet examen est déjà ajouté à la demande.");
            }
        }
    }

    /**
     * Vérifie que le contrat n'a pas atteint son plafond d'examens.
     *
     * <p>Calque de {@code TestOrderController::store()} en Laravel :
     * {@code if ($contrat->nbr_tests != -1 && $contrat->orders->count() >= $contrat->nbr_tests)}.
     * La valeur {@code -1} signifie « nombre d'examens illimité ».
     *
     * @param contratId identifiant du contrat de facturation (null = aucun contrat, rien à vérifier)
     * @param branchId  identifiant de la branche (isolation multi-tenant)
     * @throws BusinessException si le plafond du contrat est atteint
     */
    private void assertContractQuotaAvailable(UUID contratId, UUID branchId) {
        if (contratId == null) {
            return;
        }
        var contrat = contratRepository.findByIdAndBranchId(contratId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrat", contratId));
        if (contrat.getNbrTests() == -1) {
            return; // illimité
        }
        if (testOrderRepository.countByContratId(contratId) >= contrat.getNbrTests()) {
            throw new BusinessException(
                    "Échec de l'enregistrement. Le nombre d'examen de ce contrat est atteint.");
        }
    }

    private void addInvoiceDetails(Invoice invoice, List<DetailTestOrder> details) {
        for (DetailTestOrder detail : details) {
            if (!Boolean.FALSE.equals(detail.getStatus())) { // null ou true = non facturé
                InvoiceDetail invoiceDetail = new InvoiceDetail();
                invoiceDetail.setInvoice(invoice);
                invoiceDetail.setLabTest(detail.getLabTest());
                invoiceDetail.setTestName(detail.getTestName());
                invoiceDetail.setPrice(detail.getPrice());
                invoiceDetail.setDiscount(detail.getDiscount());
                BigDecimal total = detail.getTotal() != null
                        ? BigDecimal.valueOf(detail.getTotal()) : BigDecimal.ZERO;
                invoiceDetail.setTotal(total);
                invoiceDetail.setUnitPrice(detail.getPrice() != null
                        ? BigDecimal.valueOf(detail.getPrice()) : BigDecimal.ZERO);
                invoiceDetailRepository.save(invoiceDetail);

                detail.setStatus(false); // marquer comme facturé
                // sauvegarde via cascade TestOrder → DetailTestOrder
            }
        }
    }

    /**
     * Génère le prochain code unique de bon d'examen pour la branche et l'année en cours.
     *
     * <p>Format Laravel de référence : {@code {préfixe}{aa}-{séq4}} — soit {@code 26-0001}
     * avec le préfixe par défaut, qui est <b>vide</b> (cf. {@code SettingAppMissingKeysSeeder}
     * et les codes historiques « 25-3091 »). Le préfixe est le réglage
     * {@code prefixe_code_demande_examen} de la table {@code setting_apps} — la même que
     * lit le helper {@code generateCodeExamen()} de Laravel.
     * La séquence repart à {@code 0001} chaque début d'année civile.
     *
     * @param branchId identifiant de la branche pour la numérotation isolée
     * @return le code généré, non encore persisté (peut échouer sur contrainte d'unicité)
     */
    // Algorithme generateCodeExamen — équivalent Java de la fonction PHP Laravel
    // Format : {prefix}{yy2digit}-{seq4}  ex: 26-0001
    //
    // Robustesse (cas des données migrées Laravel) :
    //  - la séquence max est calculée NUMÉRIQUEMENT (pas en tri texte) et filtrée
    //    sur l'année portée par le CODE lui-même (pas created_at) ;
    //  - une pré-vérification d'existence incrémente la séquence tant que le code
    //    candidat existe déjà (trous, préfixes/longueurs hétérogènes, doublons hérités),
    //    ce qui évite de générer un code déjà pris.
    private String generateCodeExamen(UUID branchId) {
        int year = LocalDate.now().getYear();
        String yearToken = String.valueOf(year % 100);

        // Préfixe historique Laravel, lu dans `setting_apps` comme le helper PHP
        // (SettingApp::where('key','prefixe_code_demande_examen')). Vide par défaut
        // → « 26-0003 », et non « EX26-0003 » ni « ABCD26-0003 ».
        String prefix = settingAppRepository
                .findByKeyAndBranchId(PREFIXE_CODE_EXAMEN_KEY, branchId)
                .map(SettingApp::getValue)
                .map(String::trim)
                .orElse("");

        int next = testOrderRepository.findMaxSequenceForYear(branchId, yearToken) + 1;
        String code = prefix + yearToken + "-" + String.format("%04d", next);

        // Pré-vérification : saute les codes déjà existants (y compris supprimés)
        // pour ne jamais proposer un code en collision. Borne de sécurité pour
        // éviter toute boucle infinie sur des données anormales.
        int guard = 0;
        while (testOrderRepository.existsByCodeIncludingDeleted(code) && guard < 10_000) {
            next++;
            guard++;
            code = prefix + yearToken + "-" + String.format("%04d", next);
        }

        return code;
    }

    /**
     * Génère le prochain code unique de facture individuelle pour la branche et l'année en cours.
     *
     * <p>Format fixe : {@code FA{aa}{séq4}} — ex. {@code FA260001}.
     * La séquence repart à {@code 0001} chaque début d'année civile.
     *
     * @param branchId identifiant de la branche pour la numérotation isolée
     * @return le code facture généré
     */
    // Algorithme generateCodeFacture — équivalent Java de la fonction PHP Laravel
    // Format : FA{yy2digit}{seq4}  ex: FA260001
    private String generateCodeFacture(UUID branchId) {
        int year = LocalDate.now().getYear();
        List<Invoice> invoices = invoiceRepository.findByBranchIdAndCodeNotNullAndYear(
                branchId, year, PageRequest.of(0, 1));

        String seq;
        if (invoices.isEmpty()) {
            seq = "0001";
        } else {
            String lastCode = invoices.get(0).getCode();
            String last4 = lastCode.length() >= 4
                    ? lastCode.substring(lastCode.length() - 4) : lastCode;
            try {
                int next = Integer.parseInt(last4) + 1;
                seq = String.format("%04d", next);
            } catch (NumberFormatException e) {
                seq = "0001";
            }
        }

        return "FA" + (year % 100) + seq;
    }

    /**
     * Marque un bon d'examen comme livré (passage au statut DELIVERED).
     *
     * <p>Seuls les bons au statut {@code VALIDATED} peuvent être livrés.
     * Positionne également {@code report.isDelivered = true} sur le compte-rendu associé
     * pour tracer la remise physique du résultat.
     *
     * @param id identifiant UUID du bon à livrer
     * @return le bon mis à jour
     */
    @Override
    @Transactional
    public TestOrderResponseDto markAsDelivered(UUID id, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        if (order.getStatus() != TestOrderStatus.VALIDATED) {
            throw new InvalidOperationException("Seul un bon VALIDATED peut être livré.");
        }
        order.setStatus(TestOrderStatus.DELIVERED);
        testOrderRepository.save(order);

        Report report = reportRepository.findByTestOrderId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compte-rendu du bon", id));
        report.setDelivered(true);
        // Le statut du rapport doit aussi passer à DELIVERED : la liste des demandes
        // affiche reportStatus (report.getStatus()), pas seulement le drapeau delivered.
        report.setStatus(ReportStatus.DELIVERED);
        reportRepository.save(report);

        log.info("Bon d'examen livré: id={}", id);
        return testOrderMapper.toResponseDto(
                testOrderRepository.findByIdAndBranchId(id, branchId).orElseThrow());
    }

    /**
     * Assigne un médecin pathologiste à un bon d'examen.
     *
     * <p>Positionne {@code attribuateDoctorId}, {@code assignedToUserId} (même valeur)
     * et {@code assignmentDate = now()}. Alignement avec le flux Laravel complet
     * (TestOrderController.php lignes 1510–1512).
     *
     * @param id       identifiant UUID du bon
     * @param doctorId identifiant UUID du médecin à assigner
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return le bon mis à jour
     */
    @Override
    @Transactional
    public TestOrderResponseDto assignDoctor(UUID id, UUID doctorId, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        order.setAttribuateDoctorId(doctorId);
        order.setAssignedToUserId(doctorId);
        order.setAssignmentDate(LocalDateTime.now());
        log.info("Médecin assigné au bon {}: doctorId={}", id, doctorId);
        return testOrderMapper.toResponseDto(testOrderRepository.save(order));
    }

    @Override
    @Transactional
    public java.util.List<String> uploadImages(UUID id, UUID branchId, java.util.List<org.springframework.web.multipart.MultipartFile> files) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        // Laravel valide TOUTE la requête avant d'écrire quoi que ce soit
        // (`$request->validate(...)` en tête de createimagegallerie) : si un seul
        // fichier est refusé, aucun n'est enregistré. On garde ce comportement,
        // sinon un lot mixte laisserait des images à moitié rangées.
        assertJpegOrPng(files);
        java.util.List<String> existing = parseFilesName(order.getFilesName());
        java.util.List<String> dates = parseFilesName(order.getFilesAddedAt());
        // Les images déjà présentes n'ont pas de date : on comble la liste pour
        // que les index restent alignés, sans prétendre savoir quand elles sont
        // arrivées.
        while (dates.size() < existing.size()) dates.add("");
        for (org.springframework.web.multipart.MultipartFile file : files) {
            try {
                String chemin = fileStorageService.store(file);
                fichiers.rattacher(chemin, FichierStocke.TEST_ORDER, order.getId(), branchId);
                existing.add(chemin);
                dates.add(java.time.LocalDateTime.now().toString());
            } catch (java.io.IOException e) {
                throw new com.labo.anapath.common.exception.BusinessException("Erreur lors du stockage du fichier: " + file.getOriginalFilename());
            }
        }
        try {
            order.setFilesName(objectMapper.writeValueAsString(existing));
            order.setFilesAddedAt(objectMapper.writeValueAsString(dates));
        } catch (tools.jackson.core.JacksonException e) {
            throw new com.labo.anapath.common.exception.BusinessException("Erreur de sérialisation JSON");
        }
        testOrderRepository.save(order);
        return existing;
    }

    @Override
    @Transactional
    public String uploadArchive(UUID id, UUID branchId, org.springframework.web.multipart.MultipartFile file) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        try {
            order.setArchive(fileStorageService.store(file));
            fichiers.rattacher(order.getArchive(), FichierStocke.TEST_ORDER, order.getId(), branchId);
        } catch (java.io.IOException e) {
            throw new com.labo.anapath.common.exception.BusinessException(
                    "Erreur lors du stockage de la pièce jointe: " + file.getOriginalFilename());
        }
        testOrderRepository.save(order);
        return order.getArchive();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public java.util.List<HistoriquePatientDto> historiqueDuPatient(UUID testOrderId, UUID branchId) {
        TestOrder depart = testOrderRepository.findByIdAndBranchId(testOrderId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", testOrderId));
        if (depart.getPatient() == null) {
            // Une demande sans patient rattaché n'a pas d'historique — mais elle
            // se consulte quand même. Rendre la seule demande courante vaut
            // mieux qu'une erreur : l'écran dira « 1 demande », ce qui est vrai.
            return java.util.List.of(versHistorique(depart, testOrderId));
        }
        return testOrderRepository
                .findByPatientOrderByCreatedAtDesc(depart.getPatient()).stream()
                // Le cloisonnement par branche : un patient peut avoir été reçu
                // sur deux sites, et l'historique ne montre que le sien.
                .filter(o -> o.getBranchId() != null && o.getBranchId().equals(branchId))
                .map(o -> versHistorique(o, testOrderId))
                .toList();
    }

    private HistoriquePatientDto versHistorique(TestOrder o, UUID courante) {
        return new HistoriquePatientDto(
                o.getId(),
                o.getCode(),
                o.getCreatedAt(),
                o.getStatus() == null ? null : o.getStatus().name(),
                o.getId() != null && o.getId().equals(courante));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ImageDto> getImages(UUID id, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        java.util.List<String> filenames = parseFilesName(order.getFilesName());
        java.util.List<String> dates = parseFilesName(order.getFilesAddedAt());
        java.util.List<String> retraits = parseFilesName(order.getFilesDeletedAt());
        java.util.List<ImageDto> result = new java.util.ArrayList<>();
        for (int i = 0; i < filenames.size(); i++) {
            // Retirée : on ne la sert plus, mais sa case reste. L'index porté
            // par le DTO est celui du stockage, et c'est lui que le client
            // renvoie pour supprimer — le décaler viserait une autre image.
            if (i < retraits.size() && retraits.get(i) != null
                    && !retraits.get(i).isBlank()) {
                continue;
            }
            // Les images antérieures à la colonne des dates n'en ont pas : la
            // liste est plus courte, et l'absence se dit par un nul plutôt que
            // par une date inventée.
            String ajoutee = i < dates.size() ? dates.get(i) : null;
            result.add(new ImageDto(i, filenames.get(i),
                    fileStorageService.getUrl(filenames.get(i)),
                    ajoutee == null || ajoutee.isBlank()
                            ? null : java.time.LocalDateTime.parse(ajoutee)));
        }
        return result;
    }

    @Override
    @Transactional
    public void deleteImage(UUID id, int index, UUID branchId) {
        TestOrder order = testOrderRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Bon d'examen", id));
        java.util.List<String> filenames = parseFilesName(order.getFilesName());
        if (index < 0 || index >= filenames.size()) {
            throw new InvalidOperationException("Index d'image invalide: " + index);
        }

        // Suppression douce. Le fichier reste sur le disque et l'entrée à sa
        // place : ces clichés montrent ce qui a été reçu au comptoir, et une
        // suppression contestée plus tard ne doit pas avoir effacé jusqu'au
        // fait qu'une image ait existé.
        //
        // La case surtout ne bouge pas : les points d'entrée adressent les
        // images par leur index, et la retirer décalerait toutes les suivantes
        // — le client viserait alors une autre image que celle qu'il voit.
        java.util.List<String> retraits = parseFilesName(order.getFilesDeletedAt());
        while (retraits.size() < filenames.size()) retraits.add(null);
        if (retraits.get(index) != null && !retraits.get(index).isBlank()) {
            throw new InvalidOperationException("Cette image est déjà retirée.");
        }
        retraits.set(index, java.time.LocalDateTime.now().toString());
        try {
            order.setFilesDeletedAt(objectMapper.writeValueAsString(retraits));
        } catch (tools.jackson.core.JacksonException e) {
            throw new com.labo.anapath.common.exception.BusinessException("Erreur de sérialisation JSON");
        }
        testOrderRepository.save(order);
        log.info("Image retirée : bon={} index={} fichier={}", id, index, filenames.get(index));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> parseFilesName(String json) {
        if (json == null || json.isBlank()) return new java.util.ArrayList<>();
        try {
            return objectMapper.readValue(json, java.util.List.class);
        } catch (tools.jackson.core.JacksonException e) {
            return new java.util.ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Myspace
    // -------------------------------------------------------------------------

    /**
     * Calcule les statistiques des bons d'examen assignés à l'utilisateur connecté.
     *
     * <p>Le seuil de retard est fixé à 48 heures à partir de la date d'assignation.
     *
     * @param userId   identifiant de l'utilisateur
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @return DTO de statistiques
     */
    @Override
    @Transactional(readOnly = true)
    public MyspaceStatsDto getMyspaceStats(UUID userId, UUID branchId, Discipline discipline) {
        // Un super-admin voit TOUTE la donnée de la branche active (et non ses
        // seules assignations) : « Mon espace » lui sert de vue d'ensemble du labo.
        boolean seeAll = userRepository.isSuperAdmin(userId);
        // En attente / terminé sont déterminés par le statut du RAPPORT (comme Laravel),
        // car tous les bons assignés sont déjà au statut VALIDATED côté bon d'examen.
        long totalAssigned  = testOrderRepository.countByAssignedToUserIdAndBranchId(userId, branchId, seeAll, discipline);
        long totalPending   = testOrderRepository.countAssignedReportPending(userId, branchId, seeAll, discipline);
        long totalValidated = testOrderRepository.countAssignedReportDone(userId, branchId, seeAll, discipline);
        long totalUrgent    = testOrderRepository.countUrgentByAssignedToUserIdAndBranchId(userId, branchId, seeAll, discipline);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(21);
        long totalLate      = testOrderRepository.countLateByAssignedToUserIdAndBranchId(userId, branchId, cutoff, seeAll, discipline);
        // Les bons immuno sont retenus par leur type, qui n'existe qu'en
        // anatomie pathologique : ce compteur n'a pas besoin de la discipline.

        List<UUID> immunoTypeIds = typeOrderRepository.findImmunoTypeIds(branchId);
        long totalImmunoPending = immunoTypeIds.isEmpty() ? 0L
                : testOrderRepository.countImmunoPendingByAssignedToUserId(
                        userId, branchId, immunoTypeIds, seeAll);

        return new MyspaceStatsDto(totalAssigned, totalPending, totalValidated,
                totalImmunoPending, totalUrgent, totalLate);
    }

    /**
     * Retourne la page de bons d'examen assignés à l'utilisateur, avec filtres optionnels.
     *
     * @param userId   identifiant de l'utilisateur
     * @param branchId identifiant de la branche (isolation multi-tenant)
     * @param page     numéro de page (0-based)
     * @param size     taille de la page
     * @param status   filtre optionnel sur le statut
     * @param search   recherche textuelle optionnelle (code ou nom patient)
     * @return page de {@link TestOrderResponseDto}
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<TestOrderResponseDto> getMyspaceOrders(UUID userId, UUID branchId, int page, int size,
                                                               TestOrderStatus status, UUID typeOrderId,
                                                               String priority, String from, String to, String search,
                                                               Discipline discipline) {
        // La requête native findMyspaceOrders trie déjà par t.created_at DESC.
        // Ne PAS ajouter de Sort ici : Spring l'appliquerait tel quel sur la requête
        // native (ORDER BY t.createdAt) → "column t.createdat does not exist" (500).
        PageRequest pageRequest = PageRequest.of(page, size);
        String searchParam = (search != null && !search.isBlank()) ? search : null;
        String statusParam = (status != null) ? status.name() : null;
        String typeOrderParam = (typeOrderId != null) ? typeOrderId.toString() : null;
        String priorityParam = (priority != null && !priority.isBlank()) ? priority : null;
        String fromParam = (from != null && !from.isBlank()) ? from : null;
        String toParam = (to != null && !to.isBlank()) ? to : null;
        // Un super-admin voit tous les bons de la branche, pas seulement les siens.
        boolean seeAll = userRepository.isSuperAdmin(userId);
        Page<TestOrder> orderPage = testOrderRepository
                .findMyspaceOrders(userId, branchId, statusParam, typeOrderParam, priorityParam,
                        fromParam, toParam, searchParam, seeAll,
                        (discipline != null ? discipline : Discipline.PATHOLOGY).name(), pageRequest);

        // Enrichissement report + facture (batch, anti N+1) pour que la colonne
        // « Compte rendu » reflète le vrai statut (Valider / En attente / Non enregistré)
        // au lieu de rester « Non renseigné » partout.
        List<UUID> ids = orderPage.getContent().stream().map(TestOrder::getId).toList();
        Map<UUID, Report> reportMap = reportRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        r -> r.getTestOrder().getId(), r -> r, (a, b) -> a));
        Map<UUID, Invoice> invoiceMap = invoiceRepository.findByTestOrder_IdIn(ids)
                .stream().collect(Collectors.toMap(
                        i -> i.getTestOrder().getId(), i -> i, (a, b) -> a));

        Map<UUID, String> affectesA = loadAssignedUserNames(ids);

        Page<TestOrderResponseDto> result = orderPage.map(order -> enrichDto(
                testOrderMapper.toResponseDto(order),
                reportMap.get(order.getId()), invoiceMap.get(order.getId()),
                affectesA.get(order.getId())));
        return PageResponse.of(result);
    }

    // -------------------------------------------------------------------------
    // Tarification contractuelle
    // -------------------------------------------------------------------------

    /**
     * Retourne la tarification d'une analyse pour un contrat donné.
     *
     * <p>Si l'analyse figure dans le contrat, retourne le prix négocié et la remise.
     * Sinon, retourne le prix catalogue avec une remise de zéro.
     *
     * @param contratId identifiant du contrat
     * @param labTestId identifiant de l'analyse
     * @param branchId  identifiant de la branche (pour récupérer l'analyse du catalogue)
     * @return DTO de tarification
     */
    @Override
    @Transactional(readOnly = true)
    public DiscountDto getDiscount(UUID contratId, UUID labTestId, UUID branchId) {
        LabTest labTest = labTestRepository.findByIdAndBranchId(labTestId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Analyse", labTestId));
        BigDecimal basePrice = labTest.getPrice() != null ? labTest.getPrice() : BigDecimal.ZERO;

        // Résolution de la règle tarifaire comme dans Laravel : d'abord une règle
        // propre à l'analyse (lab_test_id), sinon une règle par catégorie
        // (les contrats migrés définissent surtout des remises par catégorie).
        DetailsContrat details = detailsContratRepository
                .findByContratIdAndLabTestId(contratId, labTestId)
                .orElse(null);
        if (details == null && labTest.getCategoryTest() != null) {
            details = detailsContratRepository
                    .findByContratIdAndCategoryTestId(contratId, labTest.getCategoryTest().getId())
                    .orElse(null);
        }

        if (details == null) {
            return new DiscountDto(basePrice, null, BigDecimal.ZERO, basePrice);
        }

        // La remise est un MONTANT : amount_remise s'il est défini, sinon calculé
        // depuis le pourcentage (règles par catégorie migrées : pourcentage seul).
        BigDecimal discount;
        if (details.getAmountRemise() != null && details.getAmountRemise().signum() > 0) {
            discount = details.getAmountRemise();
        } else if (details.getPourcentage() != null && details.getPourcentage().signum() > 0) {
            discount = basePrice.multiply(details.getPourcentage())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        } else {
            discount = BigDecimal.ZERO;
        }
        BigDecimal priceAfterDiscount = basePrice.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal contractPrice = details.getPrice();
        return new DiscountDto(basePrice, contractPrice, discount, priceAfterDiscount);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDIndexDto indexPourLeMobile(UUID branchId, int mois, int page, int taille,
                                           java.time.LocalDateTime jusqua) {
        // Borné des deux côtés : un appel sans limite rendrait toute
        // l'histoire du laboratoire sur un lien qu'on cherche à ménager, et
        // une profondeur démesurée reviendrait au même.
        int profondeur = Math.max(1, Math.min(mois, 36));

        // La taille de tranche est plafonnée ici et non laissée au client.
        // C'est le serveur qui connaît le poids d'une entrée ; un appel à
        // « size=100000 » rétablirait exactement le défaut qu'on corrige, et
        // le téléphone n'a aucun moyen de savoir ce qu'il demande.
        int parTranche = Math.max(50, Math.min(taille, 2000));
        int rang = Math.max(0, page);

        // Le jeu est figé au premier appel. Le client repasse ensuite
        // l'instant qu'on lui rend, de sorte que les tranches décrivent toutes
        // le même ensemble, même si le comptoir enregistre entre-temps.
        java.time.LocalDateTime borne = jusqua != null ? jusqua : java.time.LocalDateTime.now();

        var pageable = org.springframework.data.domain.PageRequest.of(rang, parTranche);
        Page<EntreeDIndexDto> tranche = testOrderRepository.indexPourLeMobile(
                branchId, borne.minusMonths(profondeur), borne, pageable);

        return new PageDIndexDto(
                tranche.getContent(),
                rang,
                parTranche,
                tranche.getTotalElements(),
                tranche.isLast(),
                borne);
    }
}

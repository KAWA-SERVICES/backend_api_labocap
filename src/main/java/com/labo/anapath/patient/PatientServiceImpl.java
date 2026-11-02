package com.labo.anapath.patient;

import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.common.exception.DuplicateResourceException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.finance.Invoice;
import com.labo.anapath.finance.InvoiceRepository;
import com.labo.anapath.finance.InvoiceStatus;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.testorder.TestOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implémentation de {@link PatientService} portant la logique métier des dossiers patients.
 * <p>
 * Les règles métier appliquées sont :
 * <ul>
 *   <li>Unicité du code patient au sein d'une agence (création et mise à jour)</li>
 *   <li>Unicité du numéro de téléphone principal au sein d'une agence</li>
 *   <li>Interdiction de suppression si le patient a des demandes d'examen</li>
 * </ul>
 * La méthode {@link #getProfile(UUID)} agrège en mémoire les données des TestOrder
 * et des Invoice pour éviter des requêtes JPQL complexes et maintenir la lisibilité.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientServiceImpl implements PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    // Utilisé pour vérifier les dépendances avant suppression et construire le profil
    private final TestOrderRepository testOrderRepository;
    // Utilisé pour construire le résumé financier du profil patient
    private final InvoiceRepository invoiceRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PatientResponseDto> findAll(int page, int size, String search, UUID branchId) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<PatientResponseDto> result;
        // Basculer sur la recherche multi-champs uniquement si un terme est fourni
        if (StringUtils.hasText(search)) {
            result = patientRepository.findByBranchIdAndSearchTerm(branchId, search, pageRequest)
                    .map(patientMapper::toResponseDto);
        } else {
            result = patientRepository.findByBranchId(branchId, pageRequest)
                    .map(patientMapper::toResponseDto);
        }
        // Agréger les totaux financiers (Total / Payé / Dû) par patient, comme le
        // tableau Laravel. Une seule requête groupée pour toute la page.
        Map<UUID, BigDecimal[]> totalsByPatient = loadInvoiceTotals(
                result.getContent().stream().map(PatientResponseDto::id).toList());
        return PageResponse.of(result.map(dto -> {
            BigDecimal[] tp = totalsByPatient.getOrDefault(
                    dto.id(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            return withTotals(dto, tp[0], tp[1]);
        }));
    }

    /**
     * Charge, pour une liste d'identifiants patients, le total facturé et le total
     * payé sous forme de {@code Map<patientId, [total, paid]>}.
     */
    private Map<UUID, BigDecimal[]> loadInvoiceTotals(List<UUID> patientIds) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal[]> map = new HashMap<>();
        for (Object[] row : invoiceRepository.sumTotalsByPatientIds(patientIds)) {
            UUID patientId = row[0] instanceof UUID u ? u : UUID.fromString(String.valueOf(row[0]));
            BigDecimal total = row[1] != null ? new BigDecimal(row[1].toString()) : BigDecimal.ZERO;
            BigDecimal paid = row[2] != null ? new BigDecimal(row[2].toString()) : BigDecimal.ZERO;
            map.put(patientId, new BigDecimal[]{total, paid});
        }
        return map;
    }

    /**
     * Recompose un {@link PatientResponseDto} en y injectant les totaux financiers
     * (le record étant immuable). Le restant dû est calculé par {@code total - paid}.
     */
    private PatientResponseDto withTotals(PatientResponseDto d, BigDecimal total, BigDecimal paid) {
        BigDecimal t = total != null ? total : BigDecimal.ZERO;
        BigDecimal p = paid != null ? paid : BigDecimal.ZERO;
        return new PatientResponseDto(
                d.id(), d.code(), d.firstname(), d.lastname(), d.genre(),
                d.telephone1(), d.telephone2(), d.adresse(), d.age(), d.yearOrMonth(),
                d.birthday(), d.profession(), d.langue(), d.email(), d.branchId(),
                d.createdAt(), t, p, t.subtract(p));
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponseDto findById(UUID id, UUID branchId) {
        Patient patient = patientRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", id));
        return patientMapper.toResponseDto(patient);
    }

    @Override
    @Transactional
    public PatientResponseDto create(PatientRequestDto dto, UUID branchId) {
        if (StringUtils.hasText(dto.getCode())
                && patientRepository.existsByCodeAndBranchId(dto.getCode(), branchId)) {
            throw new DuplicateResourceException(
                    "Un patient avec le code '" + dto.getCode() + "' existe déjà dans cette agence.");
        }
        // Le téléphone n'est pas une identité : une mère donne son numéro pour
        // ses trois enfants, un service hospitalier pour tous ses envois. Le
        // refuser bloquait un enregistrement au comptoir, devant quelqu'un qui
        // attendait — et l'agent s'en tirait en inventant un chiffre, ce qui
        // abîme la donnée bien plus sûrement qu'un doublon.
        //
        // L'identité d'un patient reste son code, dont l'unicité est vérifiée
        // ci-dessus.
        Patient patient = patientMapper.toEntity(dto);
        patient.setBranchId(branchId);
        Patient saved = patientRepository.save(patient);
        log.info("Patient créé: {}", saved.getId());
        return patientMapper.toResponseDto(saved);
    }

    @Override
    @Transactional
    public PatientResponseDto update(UUID id, PatientRequestDto dto, UUID branchId) {
        Patient patient = patientRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", id));
        // Vérifier le code uniquement s'il change réellement
        if (StringUtils.hasText(dto.getCode())
                && !dto.getCode().equals(patient.getCode())
                && patientRepository.existsByCodeAndBranchIdAndIdNot(dto.getCode(), branchId, id)) {
            throw new DuplicateResourceException(
                    "Un patient avec le code '" + dto.getCode() + "' existe déjà.");
        }
        // Pas de contrôle sur le téléphone : voir `create`. Deux patients
        // peuvent partager un numéro, et corriger celui d'une fiche ne doit pas
        // buter sur une homonymie de numéro.
        patientMapper.updateEntityFromDto(dto, patient);
        return patientMapper.toResponseDto(patientRepository.save(patient));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID branchId) {
        Patient patient = patientRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", id));
        // Interdire la suppression si des demandes d'examen référencent ce patient
        if (testOrderRepository.existsByPatient(patient)) {
            throw new BusinessException("PATIENT_HAS_ORDERS");
        }
        patientRepository.delete(patient);
        log.info("Patient supprimé (soft): {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientProfileDto getProfile(UUID id, UUID branchId) {
        Patient patient = patientRepository.findByIdAndBranchId(id, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", id));

        List<TestOrder> orders = testOrderRepository.findByPatientOrderByCreatedAtDesc(patient);
        List<Invoice> invoices = invoiceRepository.findByPatientOrderByCreatedAtDesc(patient);

        int totalOrders = orders.size();
        // Compter les demandes en attente de traitement
        int pendingOrders = (int) orders.stream()
                .filter(o -> o.getStatus() == TestOrderStatus.PENDING).count();
        // Compter les demandes validées ou livrées (considérées comme terminées)
        int completedOrders = (int) orders.stream()
                .filter(o -> o.getStatus() == TestOrderStatus.VALIDATED
                          || o.getStatus() == TestOrderStatus.DELIVERED).count();

        List<TestOrderSummaryDto> orderSummaries = orders.stream()
                .map(o -> new TestOrderSummaryDto(
                        o.getId(), o.getCode(), o.getStatus().name(),
                        o.getPrelevementDate(), o.getCreatedAt(),
                        o.getTypeOrder() == null ? null : o.getTypeOrder().getTitle()))
                .toList();

        // Calculer les totaux financiers en agrégeant toutes les factures
        BigDecimal totalInvoiced = invoices.stream()
                .map(Invoice::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaid = invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.PAID)
                .map(Invoice::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUnpaid = totalInvoiced.subtract(totalPaid);

        List<InvoiceSummaryDto> invoiceSummaries = invoices.stream()
                .map(i -> new InvoiceSummaryDto(
                        i.getId(), i.getTotal(), i.getStatus().name(),
                        i.getDueDate(), i.getCreatedAt()))
                .toList();

        return new PatientProfileDto(
                patientMapper.toResponseDto(patient),
                totalOrders, pendingOrders, completedOrders,
                orderSummaries,
                totalInvoiced, totalPaid, totalUnpaid,
                invoiceSummaries
        );
    }
}

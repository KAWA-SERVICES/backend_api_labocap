package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.common.security.UserPrincipal;
import com.labo.anapath.setting.SettingReportTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Contrôleur REST exposant les endpoints de gestion des comptes-rendus anatomopathologiques
 * ({@code /api/v1/reports}).
 *
 * <p>Opérations disponibles :
 * <ul>
 *   <li>Lister les CRs de la branche (paginé)</li>
 *   <li>Consulter un CR par identifiant</li>
 *   <li>Créer un CR manuellement (statut DRAFT)</li>
 *   <li>Mettre à jour le contenu d'un CR</li>
 *   <li>Supprimer un CR non livré</li>
 *   <li>Valider un CR (passage DRAFT → VALIDATED)</li>
 *   <li>Livrer un CR validé au destinataire</li>
 * </ul>
 *
 * <p>Chaque opération est protégée par une permission Spring Security.
 * L'isolation multi-tenant est assurée via le {@code branchId} du principal authentifié.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final NotificationService notificationService;
    private final PdfReportService pdfReportService;
    /** Contrôle d'agence des avis patient : le service, lui, sert aussi la tâche planifiée, sans agence. */
    private final ReportRepository reportRepository;

    record TemplateDto(UUID id, String name, String header, String footer, String logoPath, UUID branchId, LocalDateTime createdAt) {}

    @GetMapping
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<PageResponse<ReportResponseDto>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            // Anatomie pathologique par défaut : les clients existants n'envoient
            // pas ce paramètre et doivent voir la même liste qu'avant la biologie.
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.findAll(page, size, principal.getBranchId(), month, year, doctorId, status, search,
                        discipline)));
    }

    @GetMapping("/suivi")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<ReportSuiviDto>> getSuivi(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getSuivi(principal.getBranchId(), month, year, discipline)));
    }

    @GetMapping("/suivi/list")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<PageResponse<ReportSuiviRowDto>>> getSuiviList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String typeOrderId,
            @RequestParam(required = false) String dateBegin,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(required = false) Boolean isUrgent,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean isLate,
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @RequestParam(required = false) String rappel,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getSuiviList(
                        principal.getBranchId(), page, size,
                        search, typeOrderId, dateBegin, dateEnd,
                        isUrgent, status, isLate, discipline, rappel)));
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<PageResponse<LogReportResponseDto>>> getReportLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getReportLogs(principal.getBranchId(), page, size)));
    }

    @GetMapping("/search-global")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<PageResponse<ReportGlobalSearchRowDto>>> globalSearch(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) java.util.List<String> typeOrderIds,
            @RequestParam(required = false) java.util.List<String> contratIds,
            @RequestParam(required = false) java.util.List<String> patientIds,
            @RequestParam(required = false) java.util.List<String> doctorIds,
            @RequestParam(required = false) java.util.List<String> hospitalIds,
            @RequestParam(required = false) String referenceHospital,
            @RequestParam(required = false) String dateBegin,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) Boolean isUrgent,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.globalSearch(
                        principal.getBranchId(), page, size,
                        typeOrderIds, contratIds, patientIds, doctorIds, hospitalIds,
                        referenceHospital, dateBegin, dateEnd, content, isUrgent)));
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<PageResponse<ReportListDto>>> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateBegin,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getList(principal.getBranchId(), page, size,
                        search, status, dateBegin, dateEnd, discipline)));
    }

    @GetMapping("/performance-stats")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<ReportPerformanceDto>> getPerformanceStats(
            @RequestParam(required = false) String doctorId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getPerformanceStats(principal.getBranchId(), doctorId, month, year, discipline)));
    }

    /**
     * Retrouve un compte-rendu par le code de sa demande d'examen.
     *
     * <p>Pivot du parcours mobile : au comptoir on tient un bon d'examen, pas un
     * UUID. Le code est saisi à la main ou lu au scanner, puis résolu ici.</p>
     *
     * <p>Aucun conflit avec {@code /{id}} : ce motif compte deux segments après
     * {@code /reports}, celui-là un seul. L'ordre de déclaration n'y joue rien —
     * Spring apparie sur la forme du chemin, pas sur la position dans le fichier.</p>
     */
    @GetMapping("/by-code/{code}")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<ReportDetailDto>> findByTestOrderCode(
            @PathVariable String code,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.findDetailByTestOrderCode(code, principal.getBranchId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<ReportDetailDto>> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(reportService.findDetailById(id, principal.getBranchId())));
    }

    /**
     * Crée un compte-rendu. Un {@code reportId} dans le corps est refusé :
     * la modification passe par {@code PUT /reports/{id}}, seul chemin qui
     * trace une retouche après signature et en garde l'état antérieur.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('create-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> createOrUpdate(
            @Valid @RequestBody ReportRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (dto.getReportId() != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ReportServiceImpl.MODIFICATION_PAR_PUT));
        }
        ReportResponseDto result = reportService.createOrUpdate(dto, principal.getBranchId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Compte-rendu créé", result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> update(
            @PathVariable UUID id, @Valid @RequestBody ReportRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Compte-rendu mis à jour",
                reportService.update(id, dto, principal.getId(), principal.getBranchId())));
    }

    /**
     * Supprime (soft delete) un compte-rendu.
     *
     * @param id identifiant UUID du CR à supprimer
     * @return réponse vide 200 en cas de succès
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        reportService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Compte-rendu supprimé", null));
    }

    /**
     * Valide un compte-rendu : passage au statut VALIDATED avec journalisation.
     *
     * @param id        identifiant UUID du CR
     * @param principal principal Spring Security (fournit l'userId pour le log)
     * @return le CR validé
     */
    // Acte médical : il engage un diagnostic, et se distingue donc de la simple
    // correction d'un compte-rendu. Voir V64 pour la reprise des droits existants.
    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAuthority('validate-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> validate(
            @PathVariable UUID id,
            // Corps facultatif : l'application mobile y joint la preuve produite
            // par l'enclave sécurisée du téléphone. Le web n'envoie rien et
            // conserve le comportement d'origine.
            @Valid @RequestBody(required = false) ValidationSigneeDto preuve,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Compte-rendu validé",
                reportService.validate(id, principal.getId(), preuve)));
    }

    /**
     * Livre un compte-rendu validé au destinataire désigné.
     * Le CR doit être au statut VALIDATED pour être livré.
     *
     * @param id           identifiant UUID du CR
     * @param receiverName nom de la personne récupérant le compte-rendu
     * @param principal    principal Spring Security (fournit l'userId pour le log)
     * @return le CR passé au statut DELIVERED
     */
    // Geste de guichet : constater une remise, non rédiger ni valider.
    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> deliver(
            @PathVariable UUID id,
            @RequestParam String receiverName,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Compte-rendu livré",
                reportService.deliver(id, receiverName, principal.getId())));
    }

    @PatchMapping("/{id}/delivered-patient")
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> markDelivered(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Rapport livré au patient",
                reportService.markDelivered(id, principal.getId())));
    }

    @PatchMapping("/{id}/informed-patient")
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> markInformed(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Patient informé",
                reportService.markInformed(id, principal.getId())));
    }

    // Signature du récupérateur au comptoir : c'est ce point d'entrée que
    // l'application mobile utilisera pour le retrait.
    @PostMapping("/{id}/store-signature")
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> storeSignature(
            @PathVariable UUID id,
            @Valid @RequestBody StoreSignatureRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Signature enregistrée",
                reportService.storeSignature(id, dto, principal.getId())));
    }

    @PostMapping("/{id}/call")
    // Comme « patient informé » : le secrétariat qui remet les résultats prévient aussi.
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<CallResponseDto>> callPatient(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        dansLAgence(id, principal);
        return ResponseEntity.ok(ApiResponse.success("Appel vocal lancé",
                notificationService.callPatient(id, principal.getId())));
    }

    @PostMapping("/{id}/sms")
    // Comme « patient informé » : le secrétariat qui remet les résultats prévient aussi.
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<SmsResponseDto>> sendSms(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        dansLAgence(id, principal);
        return ResponseEntity.ok(ApiResponse.success("SMS envoyé",
                notificationService.sendSms(id, principal.getId())));
    }

    /**
     * Prévient le patient que son résultat est disponible : SMS et appel FluidChat,
     * ce dernier dans la plage réglementaire — équivalent de la route Laravel
     * {@code report.callOrSendSms}.
     */
    @PostMapping("/{id}/notify")
    // Comme « patient informé » : le secrétariat qui remet les résultats prévient aussi.
    @PreAuthorize("hasAuthority('deliver-reports')")
    public ResponseEntity<ApiResponse<NotifyResponseDto>> notifyPatient(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        dansLAgence(id, principal);
        NotifyResponseDto result = notificationService.notifyPatient(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.success(result.message(), result));
    }

    @GetMapping("/{id}/appel")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<AppelResponseDto>> getAppelStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        dansLAgence(id, principal);
        return ResponseEntity.ok(ApiResponse.success(notificationService.getAppelStatus(id)));
    }

    /**
     * 404 si le compte rendu n'est pas de l'agence courante : sans ce contrôle, un
     * UUID d'une autre agence suffisait à faire appeler son patient.
     */
    private void dansLAgence(UUID id, UserPrincipal principal) {
        reportRepository.findByIdAndBranchId(id, principal.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Compte-rendu", id));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<byte[]> generatePdf(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        ReportResponseDto report = reportService.findById(id);
        byte[] pdf = pdfReportService.generatePdf(id, principal.getId());
        String filename = "CO-" + (report.testOrderCode() != null ? report.testOrderCode() : id) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @GetMapping("/{id}/template")
    @PreAuthorize("hasAuthority('view-reports')")
    public ResponseEntity<ApiResponse<TemplateDto>> getTemplate(@PathVariable UUID id) {
        SettingReportTemplate t = reportService.getTemplate(id);
        return ResponseEntity.ok(ApiResponse.success(
                new TemplateDto(t.getId(), t.getName(), t.getHeader(), t.getFooter(),
                        t.getLogoPath(), t.getBranchId(), t.getCreatedAt())));
    }

    @PatchMapping("/{id}/template/{templateId}")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<ApiResponse<ReportResponseDto>> setTemplate(
            @PathVariable UUID id, @PathVariable UUID templateId) {
        return ResponseEntity.ok(ApiResponse.success("Modèle associé au compte-rendu",
                reportService.setTemplate(id, templateId)));
    }

    /**
     * Modifications apportées au compte-rendu après sa signature.
     *
     * <p>Route distincte de l'historique complet : celui-ci mêle impressions et
     * enregistrements ordinaires, quand la mise en exergue ne doit montrer que
     * ce qui engage la signature d'un médecin.</p>
     *
     * <p>Ouverte à {@code view-reports} : constater qu'un dossier a bougé après
     * signature relève de la lecture, pas de l'administration — un médecin doit
     * pouvoir le voir sur un compte-rendu qu'il consulte.</p>
     */
    /** Versions antérieures d'un compte-rendu signé : numéro, date, auteur, statut. */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('view-report-history')")
    public ResponseEntity<ApiResponse<List<VersionDeCompteRenduDto.Resume>>> versions(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.listerVersions(id, principal.getBranchId())));
    }

    /** Une version antérieure, textes compris. */
    @GetMapping("/{id}/versions/{numero}")
    @PreAuthorize("hasAuthority('view-report-history')")
    public ResponseEntity<ApiResponse<VersionDeCompteRenduDto>> version(
            @PathVariable UUID id, @PathVariable int numero,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.lireVersion(id, numero, principal.getBranchId())));
    }

    @GetMapping("/{id}/modifications-apres-signature")
    @PreAuthorize("hasAnyAuthority('view-reports', 'edit-reports')")
    public ResponseEntity<ApiResponse<List<ModificationApresSignatureDto>>> modificationsApresSignature(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getModificationsApresSignature(id)));
    }
}

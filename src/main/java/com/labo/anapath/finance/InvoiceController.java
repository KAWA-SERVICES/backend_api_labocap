package com.labo.anapath.finance;

import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final MecefService mecefService;
    private final FluidInvoiceService fluidInvoiceService;
    private final InvoicePdfService invoicePdfService;
    private final InvoiceSmsNotifier invoiceSmsNotifier;

    @GetMapping
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<PageResponse<InvoiceResponseDto>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean paid,
            @RequestParam(required = false) Integer statusInvoice,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                invoiceService.findAll(page, size, principal.getBranchId(), paid, statusInvoice,
                        startDate, endDate, search)));
    }

    @GetMapping("/stats/today")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<Map<String, java.math.BigDecimal>>> getTodayStats(
            @AuthenticationPrincipal UserPrincipal principal) {
        java.math.BigDecimal totalToday = invoiceService.getTotalEncashedToday(principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("totalToday", totalToday)));
    }

    /**
     * Rapport des factures, sur un mois ou sur une période libre.
     * <p>
     * Les deux formes cohabitent à dessein. {@code startDate}/{@code endDate}
     * priment et rendent une ventilation par mois ; à défaut, le couple
     * {@code year}/{@code month} historique rend un rapport mono-mois. Garder
     * l'ancienne forme évite de casser un appelant qui la pratique encore, et de
     * rendre une ventilation d'une seule ligne là où un total suffit.
     * </p>
     *
     * @param startDate premier jour de la période, inclus (ISO {@code AAAA-MM-JJ})
     * @param endDate   dernier jour de la période, inclus
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<InvoiceReportDto>> getReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal UserPrincipal principal) {

        InvoiceReportDto report = (startDate != null && endDate != null)
                ? invoiceService.getReportsForPeriod(principal.getBranchId(), startDate, endDate)
                : invoiceService.getReports(principal.getBranchId(), year, month);

        return ResponseEntity.ok(ApiResponse.success(report));
    }

    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getCounts(
            @AuthenticationPrincipal UserPrincipal principal) {
        long sales = invoiceRepository.countByBranchIdAndStatusInvoice(principal.getBranchId(), 0);
        long credits = invoiceRepository.countByBranchIdAndStatusInvoice(principal.getBranchId(), 1);
        return ResponseEntity.ok(ApiResponse.success(Map.of("sales", sales, "credits", credits)));
    }

    /**
     * Nombre de factures impayées, pour le badge « Factures » du menu
     * (helper Laravel {@code getnbrInvoicepending()}).
     *
     * @param principal utilisateur authentifié (branche active)
     * @return objet JSON {@code { "count": N }}
     */
    @GetMapping("/count-unpaid")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> countUnpaid(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("count", invoiceRepository.countUnpaidByBranchId(principal.getBranchId()))));
    }

    @GetMapping("/business")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<BusinessDashboardDto>> getBusiness(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getBusinessDashboard(principal.getBranchId())));
    }

    @GetMapping("/monthly-stats")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<java.util.List<InvoiceMonthlyStatsDto>>> getMonthlyStats(
            @RequestParam(required = false) Integer year,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                invoiceService.getMonthlyStats(principal.getBranchId(), year)));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<InvoiceSearchResultDto>> searchByPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                invoiceService.searchByPeriod(startDate, endDate, principal.getBranchId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.findById(id, principal.getBranchId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> create(
            @Valid @RequestBody InvoiceRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Facture créée", invoiceService.create(dto, principal.getBranchId())));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<byte[]> generatePdf(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        InvoiceResponseDto invoice = invoiceService.findById(id, principal.getBranchId());
        byte[] pdf = invoicePdfService.generatePdf(id, principal.getBranchId());
        String filename = "Facture-" + (invoice.code() != null ? invoice.code() : id) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @PostMapping("/from-order/{orderId}")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> createFromOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Facture créée",
                        invoiceService.createFromOrder(orderId, principal.getBranchId())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> update(
            @PathVariable UUID id,
            @Valid @RequestBody InvoiceRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Facture mise à jour", invoiceService.update(id, dto, principal.getBranchId())));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> markAsPaid(
            @PathVariable UUID id,
            @Valid @RequestBody InvoiceStatusUpdateDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Facture payée", invoiceService.markAsPaid(id, dto, principal.getBranchId())));
    }

    @PostMapping("/{id}/confirm-mecef")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> confirmMecef(
            @PathVariable UUID id,
            @Valid @RequestBody MecefConfirmRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Facture normalisée MECeF",
                mecefService.confirmInvoice(id, dto.getUid(), principal.getBranchId())));
    }

    @PostMapping("/{id}/cancel-mecef")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<Void>> cancelMecef(
            @PathVariable UUID id,
            @Valid @RequestBody MecefConfirmRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        mecefService.cancelInvoice(id, dto.getUid(), principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success("Annulation MECeF effectuée", null));
    }

    /**
     * Normalise la facture auprès de la DGI via FluidInvoice.
     *
     * <p>Renvoie la facture enrichie de son code MECeF et du lien vers le
     * document normalisé, que le client ouvre dans un nouvel onglet.</p>
     */
    @PostMapping("/{id}/normalize")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> normalize(
            @PathVariable UUID id,
            @RequestBody(required = false) InvoiceStatusUpdateDto corps,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Facture normalisée",
                fluidInvoiceService.normaliser(id, principal.getBranchId(),
                        corps == null ? null : corps.getPayment(),
                        corps == null ? null : corps.getDestinataire())));
    }

    /**
     * Envoie au client le SMS de sa facture normalisée, quand l'envoi automatique
     * n'est jamais parti. Refusé si le SMS est déjà parti.
     */
    @PostMapping("/{id}/sms")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> sendSms(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        String destinataire = invoiceSmsNotifier.envoyerALaDemande(id, principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success("SMS envoyé au " + destinataire,
                invoiceService.findById(id, principal.getBranchId())));
    }

    /**
     * Le document de la facture normalisée, relayé depuis FluidInvoice.
     *
     * <p>Le relais est nécessaire : l'adresse du document est authentifiée par
     * la clé API, que le navigateur ne doit pas connaître.</p>
     */
    @GetMapping("/{id}/normalized-document")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<byte[]> normalizedDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        InvoiceResponseDto invoice = invoiceService.findById(id, principal.getBranchId());
        byte[] pdf = fluidInvoiceService.telechargerDocument(id, principal.getBranchId());
        String filename = "Facture-normalisee-"
                + (invoice.code() != null ? invoice.code() : id) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }

    /** Crée la facture d'avoir contrepassant cette facture de vente. */
    @PostMapping("/{id}/credit-note")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> createCreditNote(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Facture d'avoir créée",
                invoiceService.createCreditNote(id, principal.getBranchId())));
    }

    /**
     * Réécrit le nom/adresse client de la facture avec les informations
     * courantes du patient rattaché. Action volontaire et tracée : la facture
     * ne se met jamais à jour toute seule.
     */
    @PostMapping("/{id}/refresh-client-info")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> refreshClientInfo(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Informations client actualisées",
                invoiceService.refreshClientInfo(id, principal.getBranchId(), principal.getId())));
    }

    /** Historique des actualisations du nom/adresse client de cette facture. */
    @GetMapping("/{id}/client-info-history")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<java.util.List<InvoiceClientInfoHistoryDto>>> getClientInfoHistory(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                invoiceService.getClientInfoHistory(id, principal.getBranchId())));
    }

    @GetMapping("/check-code")
    @PreAuthorize("hasAuthority('view-invoices')")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkCode(
            @RequestParam String code,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(Map.of("exists", invoiceService.checkCode(code, principal.getBranchId()))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        invoiceService.delete(id, principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success("Facture supprimée", null));
    }

    /**
     * Change le libellé d'une ligne de facture.
     *
     * <p>Le nom du catalogue reste enregistré : il dit quelle analyse a été
     * rendue, et c'est sur lui que reposent les statistiques. Seul l'affichage
     * change — sur le document imprimé comme dans la déclaration à la DGI, qui
     * portent ainsi le même mot.</p>
     *
     * <p>Un libellé vide défait la personnalisation. Une facture déjà
     * normalisée est refusée : corriger suppose alors un avoir.</p>
     */
    @PutMapping("/{invoiceId}/details/{detailId}/libelle")
    @PreAuthorize("hasAuthority('edit-invoices')")
    public ResponseEntity<ApiResponse<InvoiceResponseDto>> changerLibelleDeLigne(
            @PathVariable UUID invoiceId,
            @PathVariable UUID detailId,
            @RequestBody LibelleDeLigneDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Libellé mis à jour",
                invoiceService.changerLibelleDeLigne(
                        invoiceId, detailId, dto, principal.getBranchId())));
    }
}

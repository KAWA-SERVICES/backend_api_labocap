package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.PageResponse;

import java.util.List;
import java.util.UUID;

public interface ReportService {

    PageResponse<ReportResponseDto> findAll(int page, int size, UUID branchId);

    PageResponse<ReportResponseDto> findAll(int page, int size, UUID branchId, Integer month, Integer year, UUID doctorId);

    PageResponse<ReportResponseDto> findAll(int page, int size, UUID branchId, Integer month, Integer year, UUID doctorId, String status, String search);

    /**
     * Liste filtrée restreinte à une discipline.
     *
     * <p>Les surcharges sans discipline s'en tiennent à l'anatomie pathologique,
     * pour que les écrans existants voient exactement ce qu'ils voyaient.</p>
     */
    PageResponse<ReportResponseDto> findAll(int page, int size, UUID branchId, Integer month, Integer year,
                                            UUID doctorId, String status, String search, Discipline discipline);

    ReportResponseDto findById(UUID id);

    ReportDetailDto findDetailById(UUID id, UUID branchId);

    /**
     * Même détail que {@link #findDetailById}, mais désigné par le code de la
     * demande d'examen plutôt que par l'identifiant du compte-rendu.
     *
     * <p>Au comptoir on tient un bon, pas un UUID : c'est ce point d'entrée que
     * l'application mobile appelle après saisie ou scan du code.</p>
     */
    ReportDetailDto findDetailByTestOrderCode(String code, UUID branchId);

    /**
     * L'état d'un dossier désigné par le code de sa demande, sans le contenu
     * médical.
     *
     * <p>Sert le technicien, qui doit savoir de quel patient il s'agit et où en
     * est le dossier, mais n'a pas à lire le diagnostic. Voir
     * {@link DossierResumeDto}.</p>
     */
    DossierResumeDto findResumeByTestOrderCode(String code, UUID branchId);

    /**
     * Valide un compte-rendu d'anatomie pathologique en y attachant, le cas
     * échéant, la preuve d'appareil. Un compte-rendu de biologie est refusé.
     *
     * <p>{@code preuve} nul : comportement d'origine, la validation ne s'adosse
     * qu'à la session ouverte — c'est le cas du web. Non nul : la preuve est
     * vérifiée strictement et une preuve fausse fait échouer l'acte, faute de
     * quoi l'exigence se contournerait en envoyant n'importe quoi.</p>
     */
    ReportResponseDto validate(UUID id, UUID userId, ValidationSigneeDto preuve);

    /**
     * Cœur de la validation, commun aux disciplines : passage à VALIDATED, date
     * de signature, contrôle de la preuve d'appareil, journal et avis au patient
     * ({@link ReportValidatedEvent}, publié une seule fois, à la transition).
     *
     * <p><b>Ne contrôle pas la discipline.</b> Réservé aux points d'entrée qui ont
     * fait leurs propres vérifications — {@link #validate(UUID, UUID, ValidationSigneeDto)}
     * pour l'anatomie pathologique, la validation biologique pour la biologie.
     * S'exécute dans la transaction de l'appelant.</p>
     *
     * @param report compte-rendu déjà chargé par l'appelant
     * @param userId auteur de la validation
     * @param preuve preuve d'appareil, ou {@code null} depuis le web
     */
    ReportResponseDto validerCompteRendu(Report report, UUID userId, ValidationSigneeDto preuve);

    ReportResponseDto create(ReportRequestDto dto, UUID branchId);

    ReportResponseDto createOrUpdate(ReportRequestDto dto, UUID branchId);

    ReportResponseDto update(UUID id, ReportRequestDto dto, UUID userId, UUID branchId);

    void delete(UUID id);

    ReportResponseDto validate(UUID id, UUID userId);

    ReportResponseDto deliver(UUID id, String receiverName, UUID userId);

    ReportResponseDto markDelivered(UUID id, UUID userId);

    ReportResponseDto markInformed(UUID id, UUID userId);

    ReportResponseDto storeSignature(UUID id, StoreSignatureRequestDto dto, UUID userId);

    ReportSuiviDto getSuivi(UUID branchId, Integer month, Integer year);

    ReportSuiviDto getSuivi(UUID branchId, Integer month, Integer year, Discipline discipline);

    PageResponse<ReportSuiviRowDto> getSuiviList(
            UUID branchId, int page, int size,
            String search, String typeOrderId,
            String dateBegin, String dateEnd,
            Boolean isUrgent, Integer statusFilter, Boolean isLate);

    PageResponse<ReportSuiviRowDto> getSuiviList(
            UUID branchId, int page, int size,
            String search, String typeOrderId,
            String dateBegin, String dateEnd,
            Boolean isUrgent, Integer statusFilter, Boolean isLate,
            Discipline discipline);

    /**
     * @param rappel {@code A_RAPPELER} (non joint, ni informé, ni livré) ou une
     *               {@link IssueRappel} ; nul = pas de filtre
     */
    PageResponse<ReportSuiviRowDto> getSuiviList(
            UUID branchId, int page, int size,
            String search, String typeOrderId,
            String dateBegin, String dateEnd,
            Boolean isUrgent, Integer statusFilter, Boolean isLate,
            Discipline discipline, String rappel);

    PageResponse<LogReportResponseDto> getReportLogs(UUID branchId, int page, int size);

    PageResponse<ReportGlobalSearchRowDto> globalSearch(
            UUID branchId, int page, int size,
            List<String> typeOrderIds,
            List<String> contratIds,
            List<String> patientIds,
            List<String> doctorIds,
            List<String> hospitalIds,
            String referenceHospital,
            String dateBegin, String dateEnd,
            String content, Boolean isUrgent);

    com.labo.anapath.setting.SettingReportTemplate getTemplate(UUID reportId);

    ReportResponseDto setTemplate(UUID reportId, UUID templateId);

    void logAction(UUID reportId, String action, UUID userId);

    PageResponse<ReportListDto> getList(
            UUID branchId, int page, int size,
            String search, String statusFilter, String dateBegin, String dateEnd);

    PageResponse<ReportListDto> getList(
            UUID branchId, int page, int size,
            String search, String statusFilter, String dateBegin, String dateEnd,
            Discipline discipline);

    ReportPerformanceDto getPerformanceStats(
            UUID branchId, String doctorId, Integer month, Integer year);

    ReportPerformanceDto getPerformanceStats(
            UUID branchId, String doctorId, Integer month, Integer year, Discipline discipline);

    /**
     * Modifications apportées au compte-rendu après sa signature.
     *
     * @param reportId identifiant UUID du compte-rendu
     * @return liste chronologique, vide si le compte-rendu n'a pas bougé depuis
     *         sa signature — le cas de l'immense majorité des dossiers
     */
    List<ModificationApresSignatureDto> getModificationsApresSignature(UUID reportId);

    /** Versions antérieures d'un compte-rendu, de la plus ancienne à la plus récente. */
    List<VersionDeCompteRenduDto.Resume> listerVersions(UUID reportId, UUID branchId);

    /** Une version antérieure, textes compris. 404 si le compte-rendu ou le numéro n'existe pas. */
    VersionDeCompteRenduDto lireVersion(UUID reportId, int numero, UUID branchId);
}

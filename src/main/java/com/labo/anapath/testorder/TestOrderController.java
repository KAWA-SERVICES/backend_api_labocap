package com.labo.anapath.testorder;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.dto.PageResponse;
import com.labo.anapath.common.exception.DuplicateResourceException;
import com.labo.anapath.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrôleur REST exposant les endpoints de gestion des bons d'examen ({@code /api/v1/test-orders}).
 *
 * <p>Opérations disponibles :
 * <ul>
 *   <li>Lister avec filtres multi-critères (statut, patient, médecin, dates, urgence, recherche)</li>
 *   <li>Consulter un bon par identifiant</li>
 *   <li>Créer un nouveau bon (statut initial PENDING)</li>
 *   <li>Mettre à jour un bon en cours</li>
 *   <li>Supprimer (soft delete) un bon non validé</li>
 *   <li>Changer le statut vers VALIDATED (déclenchement du workflow complet)</li>
 *   <li>Marquer un bon comme DELIVERED</li>
 * </ul>
 *
 * <p>Chaque endpoint est protégé par une permission Spring Security ({@code @PreAuthorize}).
 * L'isolation multi-tenant est assurée via le {@code branchId} extrait du principal authentifié.
 */
@RestController
@RequestMapping("/api/v1/test-orders")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class TestOrderController {

    private final TestOrderService testOrderService;
    private final com.labo.anapath.report.ReportService reportService;
    private final com.labo.anapath.discussion.DiscussionService discussionService;

    /**
     * Retourne la liste paginée des bons d'examen de la branche de l'utilisateur connecté,
     * filtrée selon les critères optionnels fournis en paramètres de requête.
     *
     * @param page      numéro de page (0-based, défaut 0)
     * @param size      taille de la page (défaut 20)
     * @param status    filtre sur le statut du bon
     * @param patientId filtre sur le patient
     * @param doctorId  filtre sur le médecin prescripteur
     * @param hospitalId filtre sur l'hôpital d'origine
     * @param isUrgent  filtre sur l'urgence
     * @param from      date de prélèvement minimale (inclusif)
     * @param to        date de prélèvement maximale (inclusif)
     * @param search    recherche textuelle sur le code du bon
     * @param principal principal Spring Security contenant le branchId
     * @return page de {@link TestOrderResponseDto}
     */
    /**
     * L'état du dossier attaché à une demande, désigné par son code.
     *
     * <p>Sert le technicien : scanner le QR d'un bon, ou en saisir le code,
     * pour savoir de quel patient il s'agit et où en est le dossier. Il ne
     * reçoit pas le contenu médical — voir {@link com.labo.anapath.report.DossierResumeDto}.</p>
     *
     * <p>Distinct de {@code /reports/by-code}, gardé par {@code view-reports} :
     * chercher une demande d'examen relève de {@code view-test-orders}, le
     * droit qui correspond au geste. Sans cette distinction, organiser des
     * prélèvements supposerait d'avoir accès aux diagnostics.</p>
     */
    @GetMapping("/by-code/{code}")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<com.labo.anapath.report.DossierResumeDto>> findDossierByCode(
            @PathVariable String code,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.findResumeByTestOrderCode(code, principal.getBranchId())));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<PageResponse<TestOrderResponseDto>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TestOrderStatus status,
            @RequestParam(required = false) String reportStatus,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) UUID attribuateDoctorId,
            @RequestParam(required = false) UUID hospitalId,
            @RequestParam(required = false) Boolean isUrgent,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) Boolean enCours,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID contratId,
            @RequestParam(required = false) UUID typeOrderId,
            // Anatomie pathologique par défaut : les clients existants n'envoient
            // pas ce paramètre et doivent voir la même liste qu'avant la biologie.
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setDiscipline(discipline);
        filter.setStatus(status);
        filter.setReportStatus(reportStatus);
        filter.setPatientId(patientId);
        filter.setDoctorId(doctorId);
        filter.setAttribuateDoctorId(attribuateDoctorId);
        filter.setHospitalId(hospitalId);
        filter.setIsUrgent(isUrgent);
        filter.setAnnee(annee);
        filter.setEnCours(enCours);
        filter.setFrom(from);
        filter.setTo(to);
        filter.setSearch(search);
        filter.setContratId(contratId);
        filter.setTypeOrderId(typeOrderId);
        return ResponseEntity.ok(ApiResponse.success(testOrderService.findAll(page, size, filter, principal.getBranchId())));
    }

    // -------------------------------------------------------------------------
    // Immunohistochimie
    // -------------------------------------------------------------------------

    /**
     * Retourne la liste paginée des bons d'examen de la section Immunohistochimie
     * (types {@code immuno-interne} et {@code immuno-exterme}), filtrée selon
     * les critères optionnels fournis en paramètres de requête.
     *
     * @param page                numéro de page (0-based, défaut 0)
     * @param size                taille de la page (défaut 20)
     * @param status              filtre sur le statut du bon
     * @param isUrgent            filtre sur l'urgence
     * @param from                date de prélèvement minimale (inclusif)
     * @param to                  date de prélèvement maximale (inclusif)
     * @param search              recherche textuelle sur le code du bon
     * @param contratId           filtre sur le contrat
     * @param attribuateDoctorId  filtre sur le pathologiste assigné
     * @param principal           principal Spring Security contenant le branchId
     * @return page de {@link TestOrderResponseDto}
     */
    @GetMapping("/immuno")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<PageResponse<TestOrderResponseDto>>> findAllImmuno(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TestOrderStatus status,
            @RequestParam(required = false) Boolean isUrgent,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) Boolean enCours,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID contratId,
            @RequestParam(required = false) UUID attribuateDoctorId,
            @AuthenticationPrincipal UserPrincipal principal) {
        TestOrderFilterDto filter = new TestOrderFilterDto();
        filter.setStatus(status);
        filter.setIsUrgent(isUrgent);
        filter.setAnnee(annee);
        filter.setEnCours(enCours);
        filter.setFrom(from);
        filter.setTo(to);
        filter.setSearch(search);
        filter.setContratId(contratId);
        filter.setAttribuateDoctorId(attribuateDoctorId);
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.findAllImmuno(page, size, filter, principal.getBranchId())));
    }

    /**
     * Retourne le nombre de bons immuno dont le rapport est en statut DRAFT
     * (ou inexistant), pour alimenter le badge sidebar.
     *
     * @param principal principal Spring Security contenant le branchId
     * @return objet JSON {@code { "count": N }}
     */
    @GetMapping("/immuno/count-pending")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Long>>> countImmunoPending(
            @AuthenticationPrincipal UserPrincipal principal) {
        long count = testOrderService.countImmunoPending(principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of("count", count)));
    }

    /**
     * Retourne le nombre de bons de cytologie/histologie en attente de compte rendu,
     * pour le badge « Demandes d'examen » du menu (helper Laravel
     * {@code getnbrTestOrderpending()}).
     *
     * @param principal principal Spring Security contenant le branchId
     * @return objet JSON {@code { "count": N }}
     */
    @GetMapping("/count-pending")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Long>>> countPending(
            @AuthenticationPrincipal UserPrincipal principal) {
        long count = testOrderService.countPending(principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of("count", count)));
    }

    // -------------------------------------------------------------------------
    // Myspace
    // -------------------------------------------------------------------------

    /**
     * Retourne les statistiques des bons d'examen assignés à l'utilisateur connecté.
     *
     * @param principal principal Spring Security contenant userId et branchId
     * @return DTO de statistiques (total, pending, validated, urgent, late)
     */
    @GetMapping("/myspace/stats")
    @PreAuthorize("hasAuthority('view-test-order-assignments')")
    public ResponseEntity<ApiResponse<MyspaceStatsDto>> getMyspaceStats(
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.getMyspaceStats(principal.getId(), principal.getBranchId(), discipline)));
    }

    /**
     * Retourne la liste paginée des bons d'examen assignés à l'utilisateur connecté.
     *
     * @param page      numéro de page (0-based, défaut 0)
     * @param size      taille de la page (défaut 20)
     * @param status    filtre optionnel sur le statut du bon
     * @param search    recherche textuelle optionnelle (code du bon ou nom du patient)
     * @param principal principal Spring Security contenant userId et branchId
     * @return page de {@link TestOrderResponseDto}
     */
    @GetMapping("/myspace/orders")
    @PreAuthorize("hasAuthority('view-test-order-assignments')")
    public ResponseEntity<ApiResponse<PageResponse<TestOrderResponseDto>>> getMyspaceOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TestOrderStatus status,
            @RequestParam(required = false) UUID typeOrderId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "PATHOLOGY") Discipline discipline,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.getMyspaceOrders(
                        principal.getId(), principal.getBranchId(), page, size,
                        status, typeOrderId, priority, from, to, search, discipline)));
    }

    // -------------------------------------------------------------------------
    // Tarification contractuelle
    // -------------------------------------------------------------------------

    /**
     * Retourne la tarification d'une analyse pour un contrat donné.
     *
     * <p>Si l'analyse est référencée dans le contrat, retourne le prix négocié et la remise.
     * Sinon, retourne le prix catalogue avec remise nulle.
     *
     * @param contratId identifiant UUID du contrat
     * @param labTestId identifiant UUID de l'analyse
     * @param principal principal Spring Security contenant le branchId
     * @return DTO de tarification ({@link DiscountDto})
     */
    @GetMapping("/discount")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<DiscountDto>> getDiscount(
            @RequestParam UUID contratId,
            @RequestParam UUID labTestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.getDiscount(contratId, labTestId, principal.getBranchId())));
    }

    // -------------------------------------------------------------------------

    /**
     * Retourne le détail d'un bon d'examen par son identifiant.
     * L'isolation multi-tenant est assurée via le branchId du principal authentifié.
     *
     * @param id        identifiant UUID du bon
     * @param principal principal Spring Security contenant le branchId
     * @return le bon correspondant, ou 404 s'il n'existe pas ou n'appartient pas à la branche
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(testOrderService.findById(id, principal.getBranchId())));
    }

    /**
     * Crée un nouveau bon d'examen au statut PENDING pour la branche de l'utilisateur connecté.
     *
     * @param dto       données du bon à créer (patient, date de prélèvement, analyses, etc.)
     * @param principal principal Spring Security contenant le branchId
     * @return le bon créé avec le code HTTP 201
     */
    @PostMapping
    @PreAuthorize("hasAuthority('create-test-orders')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> create(
            @Valid @RequestBody TestOrderRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        TestOrderResponseDto created = testOrderService.create(dto, principal.getBranchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Bon d'examen créé", created));
    }

    /**
     * Met à jour les informations d'un bon d'examen existant.
     * Seuls les bons au statut PENDING peuvent être modifiés.
     * L'isolation multi-tenant est assurée via le branchId du principal authentifié.
     *
     * @param id        identifiant UUID du bon à mettre à jour
     * @param dto       nouvelles données
     * @param principal principal Spring Security contenant le branchId
     * @return le bon mis à jour
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('edit-test-orders')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> update(
            @PathVariable UUID id,
            @Valid @RequestBody TestOrderRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Bon d'examen mis à jour", testOrderService.update(id, dto, principal.getBranchId())));
    }

    /**
     * Supprime (soft delete) un bon d'examen non encore validé.
     * L'isolation multi-tenant est assurée via le branchId du principal authentifié.
     *
     * @param id        identifiant UUID du bon à supprimer
     * @param principal principal Spring Security contenant le branchId
     * @return réponse vide 200 en cas de succès
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('delete-test-orders')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        testOrderService.delete(id, principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success("Bon d'examen supprimé", null));
    }

    /**
     * Déclenche le workflow de validation du bon d'examen (passage au statut VALIDATED).
     *
     * <p>Ce endpoint orchestre : génération du code unique, création du compte-rendu DRAFT,
     * journalisation dans LogReport et facturation selon le type de contrat.
     *
     * @param id        identifiant UUID du bon
     * @param status    valeur attendue : {@code "VALIDATED"}
     * @param principal principal Spring Security (fournit userId et branchId)
     * @return le bon mis à jour avec son code généré
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('edit-test-orders')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> updateStatus(
            @PathVariable UUID id,
            @RequestParam String status,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Retry transactionnel : en cas de collision de code (deux validations
        // concurrentes), chaque appel de updateStatus s'exécute dans une NOUVELLE
        // transaction (via le proxy Spring). La perdante est proprement annulée
        // (rien de partiel n'est commité) puis rejouée : elle relit alors le code
        // gagnant et calcule le suivant. L'utilisateur ne voit plus le conflit.
        int maxAttempts = 5;
        for (int attempt = 1; ; attempt++) {
            try {
                return ResponseEntity.ok(ApiResponse.success("Statut mis à jour",
                        testOrderService.updateStatus(id, status, principal.getId(), principal.getBranchId())));
            } catch (DuplicateResourceException e) {
                // On ne rejoue que la collision de génération de code, et pas indéfiniment.
                if (!"CODE_GENERATION_CONFLICT".equals(e.getMessage()) || attempt >= maxAttempts) {
                    throw e;
                }
            }
        }
    }

    /**
     * Marque un bon d'examen comme livré (passage au statut DELIVERED).
     *
     * <p>Seuls les bons au statut VALIDATED peuvent être livrés. Positionne également
     * {@code report.isDelivered = true} sur le compte-rendu associé.
     *
     * @param id        identifiant UUID du bon à livrer
     * @param principal principal Spring Security contenant le branchId
     * @return le bon mis à jour
     */
    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> deliver(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Bon livré", testOrderService.markAsDelivered(id, principal.getBranchId())));
    }

    /**
     * Assigne un médecin pathologiste à un bon d'examen.
     *
     * <p>Positionne {@code attribuateDoctorId}, {@code assignedToUserId} et {@code assignmentDate}
     * sur le bon. Aucun guard sur le statut — alignement avec le comportement Laravel.
     *
     * @param id        identifiant UUID du bon
     * @param dto       corps de la requête contenant le {@code doctorId}
     * @param principal principal Spring Security contenant le branchId
     * @return le bon mis à jour avec le médecin assigné
     */
    @PostMapping("/{id}/images")
    @PreAuthorize("hasAuthority('edit-test-orders')")
    public ResponseEntity<ApiResponse<List<String>>> uploadImages(
            @PathVariable UUID id,
            @RequestParam("files_name") List<MultipartFile> files,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(testOrderService.uploadImages(id, principal.getBranchId(), files)));
    }

    /**
     * Les autres demandes du même patient.
     *
     * <p>Gardé par {@code view-test-orders}, comme la consultation d'une
     * demande : savoir qu'un patient est déjà venu relève du même droit que
     * consulter le dossier d'où l'on part. Aucun contenu médical n'y figure —
     * un code, une date, un état.</p>
     */
    @GetMapping("/{id}/historique-patient")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<List<HistoriquePatientDto>>> historiquePatient(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.historiqueDuPatient(id, principal.getBranchId())));
    }

    @GetMapping("/{id}/images")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<List<ImageDto>>> getImages(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(testOrderService.getImages(id, principal.getBranchId())));
    }

    @DeleteMapping("/{id}/images/{index}")
    @PreAuthorize("hasAuthority('edit-test-orders')")
    public ResponseEntity<ApiResponse<Void>> deleteImage(
            @PathVariable UUID id,
            @PathVariable int index,
            @AuthenticationPrincipal UserPrincipal principal) {
        testOrderService.deleteImage(id, index, principal.getBranchId());
        return ResponseEntity.ok(ApiResponse.success("Image supprimée", null));
    }

    /**
     * Téléverse la pièce jointe (archive) d'un bon d'examen — équivalent du champ
     * {@code examen_file} de Laravel. Le fichier est stocké et son chemin est
     * enregistré dans la colonne {@code archive} du bon.
     *
     * @param id        identifiant UUID du bon
     * @param file      fichier envoyé (multipart, paramètre {@code archive})
     * @param principal principal Spring Security (branchId)
     * @return le chemin du fichier stocké
     */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyAuthority('create-test-orders', 'edit-test-orders')")
    public ResponseEntity<ApiResponse<String>> uploadArchive(
            @PathVariable UUID id,
            @RequestParam("archive") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Pièce jointe enregistrée",
                testOrderService.uploadArchive(id, principal.getBranchId(), file)));
    }

    @PostMapping("/{id}/assign-doctor")
    @PreAuthorize("hasAuthority('edit-test-orders')")
    public ResponseEntity<ApiResponse<TestOrderResponseDto>> assignDoctor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignDoctorRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                "Médecin assigné",
                testOrderService.assignDoctor(id, dto.doctorId(), principal.getBranchId())));
    }

    /**
     * L'index compact des demandes, pour le travail hors ligne du mobile.
     *
     * <p>L'application le garde sur l'appareil et s'en sert à résoudre un code
     * scanné sans réseau. Sans lui, un technicien hors couverture ne peut pas
     * même savoir à quelle demande correspond le tube qu'il tient, et tout le
     * reste du travail hors ligne s'arrête là.</p>
     *
     * <p>Quatre champs par demande, et le périmètre borné au jeu de travail :
     * mesuré sur la base de production, l'index pèse environ 370 Ko quand la
     * même liste au format complet en pèse 4,1. C'est onze fois moins sur le
     * lien que ce dispositif existe justement pour ménager.</p>
     *
     * <p>[mois] remonte dans le passé depuis aujourd'hui. Douze par défaut :
     * une demande plus ancienne et non remise existe, mais elle ne revient plus
     * au laboratoire sous forme de tube à scanner.</p>
     *
     * <h2>Pourquoi il se rend par tranches</h2>
     *
     * <p>Il tenait en un seul appel, et c'était le défaut. Mesuré sur le jeu de
     * travail, cet appel rend 709 Ko ; l'application borne une requête à vingt
     * secondes, soit 284 kbit/s à tenir de bout en bout. En EDGE ou en 3G
     * faible, le délai expire et rien n'est gardé — le téléphone jette six
     * cents kilo-octets déjà descendus, et le nouvel essai repart de zéro.
     * L'index n'arrivait donc jamais là où il sert le plus.</p>
     *
     * <p>Par tranches de cinq cents, chaque appel pèse une soixantaine de
     * kilo-octets. Ce qui est arrivé est gardé : une coupure à la septième
     * tranche en laisse six acquises au lieu de rien.</p>
     *
     * <p>[jusqua] fige le jeu : le serveur le rend au premier appel, le client
     * le repasse aux suivants. Sans lui, une demande enregistrée au comptoir
     * pendant le rapatriement décalerait les pages et en ferait manquer une
     * entrée.</p>
     */
    /**
     * Tout ce que l'ouverture d'un dossier demande, pour plusieurs dossiers à
     * la fois.
     *
     * <p>L'application descend d'avance les lots confiés au laboratoire pour
     * qu'ils s'ouvrent sans réseau. Les lots seuls ne suffisent pas : toucher
     * un dossier demande ses images, l'historique de son patient et son fil,
     * soit trois appels. Pour les 991 demandes des soixante lots préchargés,
     * cela faisait 2 973 requêtes — une demi-heure sur un lien de brousse, et
     * autant d'occasions d'échouer. Groupées par cinquante, une vingtaine
     * suffisent.</p>
     *
     * <p>Le fil est LU et jamais créé : l'ouverture ordinaire d'une discussion
     * le crée s'il manque, ce qui est juste quand quelqu'un vient y écrire mais
     * ouvrirait ici neuf cents fils vides.</p>
     *
     * <p>Un dossier introuvable est simplement absent de la réponse. Faire
     * échouer l'appel entier pour une demande supprimée entre-temps priverait
     * l'agent des quarante-neuf autres.</p>
     */
    @GetMapping("/dossiers-hors-ligne")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<java.util.List<DossierHorsLigneDto>>> dossiersHorsLigne(
            @RequestParam java.util.List<UUID> ids,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Plafonné au serveur : lui seul connaît le poids d'une réponse, et un
        // appel à « ids=<mille> » rétablirait le défaut qu'on corrige.
        var demandes = ids.size() > 50 ? ids.subList(0, 50) : ids;

        var dossiers = new java.util.ArrayList<DossierHorsLigneDto>(demandes.size());
        for (UUID id : demandes) {
            try {
                // La fiche en fait partie, et c'est une correction : je l'avais
                // écartée en jugeant que l'index local du mobile suffisait. Il
                // ne porte que code, patient et statut — pas la chronologie.
                // Constaté sur l'appareil : un dossier ouvert sans réseau
                // affichait « date d'enregistrement inconnue » et ne savait
                // dire ni l'affectation ni le retrait.
                //
                // Sans la signature du récupérateur, en revanche : 14 Ko en
                // moyenne, et elle ne sert qu'à l'écran qui la montre en grand.
                // La descendre pour mille cinq cents dossiers coûterait plus
                // que tout le reste réuni, pour une image qu'on ouvre rarement.
                // Le code sert deux fois : l'application garde la fiche sous
                // sa clé, et la fiche elle-même se lit par lui. Une demande
                // encore en attente n'en a pas — elle sort alors par le `catch`
                // plus bas, ce qui est juste : sans code, rien à scanner.
                String code = testOrderService.findById(
                        id, principal.getBranchId()).code();
                var fiche = reportService.findResumeByTestOrderCode(
                        code, principal.getBranchId());
                dossiers.add(new DossierHorsLigneDto(
                        id,
                        code,
                        sansLaSignature(fiche),
                        testOrderService.getImages(id, principal.getBranchId()),
                        testOrderService.historiqueDuPatient(id, principal.getBranchId()),
                        discussionService.filSansCreer(
                                id, principal.getId(), principal.getBranchId())));
            } catch (RuntimeException e) {
                // Supprimée, hors branche, ou sans droit sur le fil : on passe.
                log.debug("Dossier {} écarté du préchargement : {}", id, e.toString());
            }
        }
        return ResponseEntity.ok(ApiResponse.success(dossiers));
    }

    /** La même fiche, privée de la signature du récupérateur. */
    private static com.labo.anapath.report.DossierResumeDto sansLaSignature(
            com.labo.anapath.report.DossierResumeDto f) {
        if (f == null || f.retrieverSignature() == null) return f;
        return new com.labo.anapath.report.DossierResumeDto(
                f.id(), f.code(), f.testOrderId(), f.testOrderCode(),
                f.patientName(), f.titleName(), f.status(), f.isDelivered(),
                f.retrieverName(), f.retrieverRelation(), null,
                f.demandeCreatedAt(), f.deliveryDate(),
                f.assignedToName(), f.assignmentCode(), f.assignmentDate(),
                f.assignmentLabels(), f.assignmentNote(), f.assignmentLotNote(),
                f.medecinsPourAvis(), f.avis());
    }

    @GetMapping("/index")
    @PreAuthorize("hasAuthority('view-test-orders')")
    public ResponseEntity<ApiResponse<PageDIndexDto>> index(
            @RequestParam(defaultValue = "12") int mois,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int taille,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime jusqua,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                testOrderService.indexPourLeMobile(
                        principal.getBranchId(), mois, page, taille, jusqua)));
    }
}

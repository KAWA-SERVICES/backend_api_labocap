package com.labo.anapath.common.exception;

import com.labo.anapath.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Gestionnaire global des exceptions de l'API.
 * <p>
 * Intercepte toutes les exceptions applicatives et les transforme en réponses
 * JSON structurées via {@link ApiResponse}, avec le code HTTP approprié.
 * Ce handler est le point centralisé de traduction exception → réponse HTTP ;
 * aucun contrôleur ne doit gérer les exceptions directement.
 * </p>
 * <ul>
 *   <li>{@link ResourceNotFoundException} → 404 Not Found</li>
 *   <li>{@link BusinessException} → 422 Unprocessable Entity</li>
 *   <li>{@link DuplicateResourceException} → 409 Conflict</li>
 *   <li>{@link InvalidOperationException} → 400 Bad Request</li>
 *   <li>{@link UnauthorizedException} → 401 Unauthorized</li>
 *   <li>{@link InvalidCodeException} → 400 Bad Request</li>
 *   <li>{@link AccessDeniedException} → 403 Forbidden</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request (détails par champ)</li>
 *   <li>{@link Exception} (générique) → 500 Internal Server Error</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Gère les ressources introuvables en base de données.
     *
     * @param ex exception levée
     * @return réponse 404 avec le message d'erreur
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Route inconnue (robot qui tâte /wp-admin, faute de frappe dans une URL,
     * point de terminaison actuator éteint). Sans ce gestionnaire, elle tombait
     * dans le cas générique et répondait 500 : un simple scan aurait déclenché
     * l'alerte « erreurs serveur » de la supervision.
     *
     * @param ex exception levée par le gestionnaire de ressources statiques
     * @return réponse 404
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Ressource introuvable."));
    }

    /**
     * Gère les violations de règles métier.
     *
     * @param ex exception levée
     * @return réponse 422 avec le message d'erreur
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère les tentatives de création d'une ressource en doublon.
     *
     * @param ex exception levée
     * @return réponse 409 avec le message d'erreur
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Les refus de l'assistant IA avant toute réponse en flux : chacun porte
     * son code HTTP (409 conversation occupée, 429 quota ou débit, 503 pas
     * configuré…). Fonctionnement attendu, pas un incident.
     *
     * @param ex exception levée
     * @return réponse au code porté par l'exception
     */
    @ExceptionHandler(com.labo.anapath.assistant.AssistantException.class)
    public ResponseEntity<ApiResponse<Object>> handleAssistant(com.labo.anapath.assistant.AssistantException ex) {
        log.info("Assistant : {} — {}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.statut()).body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère la réaffectation d'une demande déjà confiée à un autre médecin.
     *
     * <p>Journalisée en information et non en avertissement : refuser une
     * réaffectation non confirmée est le fonctionnement attendu, pas un
     * incident. La noter en avertissement ferait chercher un défaut là où il
     * n'y a qu'une question posée à l'utilisateur.</p>
     *
     * @param ex exception levée
     * @return réponse 409 nommant le médecin en place
     */
    @ExceptionHandler(ReaffectationNonConfirmeeException.class)
    public ResponseEntity<ApiResponse<Object>> handleReaffectation(
            ReaffectationNonConfirmeeException ex) {
        log.info("Réaffectation à confirmer : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère les opérations structurellement invalides dans le contexte courant.
     *
     * @param ex exception levée
     * @return réponse 400 avec le message d'erreur
     */
    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidOperation(InvalidOperationException ex) {
        log.warn("Invalid operation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère les échecs d'authentification (token absent, invalide ou révoqué).
     *
     * @param ex exception levée
     * @return réponse 401 avec le message d'erreur
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère les codes de vérification invalides (notamment les codes TOTP 2FA).
     *
     * @param ex exception levée
     * @return réponse 400 avec le message d'erreur
     */
    @ExceptionHandler(InvalidCodeException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidCode(InvalidCodeException ex) {
        log.warn("Invalid code: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Gère les refus d'accès levés par Spring Security (permissions insuffisantes).
     * <p>
     * Le message exact de l'exception n'est pas exposé au client pour éviter
     * toute fuite d'information sur la politique de contrôle d'accès.
     * </p>
     *
     * @param ex exception levée par Spring Security
     * @return réponse 403 avec un message générique
     */
    /**
     * Refus dont le motif est destiné à être lu par la personne.
     *
     * <p>Déclaré à côté du cas général : Spring retient le gestionnaire le plus
     * spécifique, et celui-ci rend le message tel qu'il a été écrit au lieu du
     * texte générique. Voir {@link AccesRefuseExplique} pour ce qui justifie
     * cette exception à la règle de prudence ci-dessus.</p>
     */
    @ExceptionHandler(AccesRefuseExplique.class)
    public ResponseEntity<ApiResponse<Object>> handleAccesRefuseExplique(
            AccesRefuseExplique ex, jakarta.servlet.http.HttpServletRequest requete) {
        log.warn("Accès refusé (motif montré) : {} {} — {}",
                requete.getMethod(), requete.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(
            AccessDeniedException ex, jakarta.servlet.http.HttpServletRequest requete) {
        // Le chemin et la personne, pas seulement « Access Denied ».
        //
        // Sans eux, la ligne n'apprend rien : on sait qu'un refus a eu lieu,
        // jamais lequel. Neuf refus identiques dans un journal ne se
        // distinguent pas d'un seul répété, et rien ne dit s'ils viennent d'un
        // agent qui n'a pas le droit ou d'une route mal configurée.
        Object qui = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() == null
                ? "anonyme"
                : org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication().getName();
        log.warn("Accès refusé : {} {} par {} — {}",
                requete.getMethod(), requete.getRequestURI(), qui, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Accès refusé : vous n'avez pas les droits nécessaires."));
    }

    /**
     * Gère les erreurs de validation Bean Validation ({@code @Valid}) en renvoyant
     * la liste de tous les champs en erreur.
     *
     * @param ex exception de validation levée par Spring MVC
     * @return réponse 400 avec une map {@code champ → message d'erreur}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation errors: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, "Erreurs de validation", errors,
                        java.time.LocalDateTime.now()));
    }

    /**
     * Gère les violations de contraintes Bean Validation sur les @RequestParam / @PathVariable
     * ainsi qu'au niveau service/repository. Collecte toutes les violations et les concatène.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Constraint violation: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    /**
     * Gère les erreurs d'API externe (timeout, connexion refusée, etc.).
     *
     * @param ex exception levée
     * @return réponse 503 avec le message d'erreur
     */
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleExternalApi(ExternalApiException ex) {
        log.error("External API error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Filet de sécurité pour toute exception non interceptée par les handlers spécialisés.
     * <p>
     * Journalise la stack trace complète en erreur et retourne une réponse générique
     * sans exposer les détails techniques au client.
     * </p>
     *
     * @param ex exception imprévue
     * @return réponse 500 avec un message générique
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Une erreur interne est survenue."));
    }
}

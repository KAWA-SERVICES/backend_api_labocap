package com.labo.anapath.user;

import com.labo.anapath.dashboard.DashboardDto;
import com.labo.anapath.dashboard.DashboardProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository JPA pour l'entité {@link User}.
 *
 * <p>Les requêtes de recherche et d'existence sont automatiquement filtrées
 * par la restriction Hibernate {@code deleted_at IS NULL} définie sur l'entité,
 * garantissant que les utilisateurs supprimés logiquement sont invisibles.</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Recherche un utilisateur actif par son adresse e-mail.
     *
     * @param email adresse e-mail à rechercher
     * @return un {@link Optional} contenant l'utilisateur trouvé, ou vide
     */
    Optional<User> findByEmail(String email);

    /**
     * Recherche un utilisateur par son identifiant et sa succursale.
     * Assure l'isolation multi-tenant : un utilisateur ne peut être accédé que par sa succursale.
     *
     * @param id       identifiant UUID de l'utilisateur
     * @param branchId identifiant de la succursale
     * @return l'utilisateur s'il appartient à la succursale, sinon vide
     */
    Optional<User> findByIdAndBranchId(UUID id, UUID branchId);

    /**
     * Vérifie si un utilisateur actif existe avec l'adresse e-mail donnée.
     * Utilisé pour détecter les doublons avant création ou modification.
     *
     * @param email adresse e-mail à vérifier
     * @return {@code true} si l'e-mail est déjà utilisé
     */
    boolean existsByEmail(String email);

    /**
     * Retourne la liste paginée des utilisateurs d'une succursale donnée.
     *
     * @param branchId identifiant de la succursale
     * @param pageable paramètres de pagination et de tri
     * @return page d'utilisateurs
     */
    Page<User> findByBranchId(UUID branchId, Pageable pageable);

    /**
     * Vérifie si au moins un utilisateur est rattaché à la succursale donnée.
     *
     * @param branchId identifiant de la succursale
     * @return {@code true} si la succursale possède des utilisateurs
     */
    boolean existsByBranchId(UUID branchId);

    /**
     * Recherche un utilisateur par son token de réinitialisation de mot de passe.
     *
     * @param resetToken token UUID de réinitialisation
     * @return un {@link Optional} contenant l'utilisateur trouvé, ou vide
     */
    Optional<User> findByResetToken(String resetToken);

    /**
     * Indique si au moins un utilisateur actif (non supprimé) est rattaché au rôle donné.
     * Sert à empêcher la suppression d'un rôle encore attribué à des utilisateurs.
     *
     * @param roleId identifiant du rôle
     * @return {@code true} si le rôle est lié à au moins un utilisateur actif
     */
    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.roles r WHERE r.id = :roleId")
    boolean existsByRoleId(@Param("roleId") UUID roleId);

    /**
     * Indique si l'utilisateur possède le rôle « Super Admin » (slug {@code super-admin}).
     * <p>
     * Utilisé par « Mon espace » pour élargir la vue d'un super-admin à l'ensemble
     * des bons d'examen de la branche active, et non à ses seules assignations.
     * </p>
     *
     * @param userId identifiant de l'utilisateur
     * @return {@code true} si l'utilisateur est super-admin
     */
    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.roles r WHERE u.id = :userId AND r.slug = 'super-admin'")
    boolean isSuperAdmin(@Param("userId") UUID userId);

    /**
     * Dashboard — utilisateurs connectés.
     *
     * Parité Laravel : {@code users->where('is_connect', 1)->whereDate('updated_at', today)}.
     * Le drapeau {@code is_connect} n'est pas fiable seul — il reste à vrai quand
     * une session se termine sans déconnexion explicite (17 comptes le portent
     * en base pour 2 réellement actifs du jour) : sans la condition de date, le
     * tableau listait des utilisateurs déconnectés depuis des mois.
     */
    @Query(value = """
            SELECT u.id::text as id, u.lastname as lastname, u.firstname as firstname, u.email as email
            FROM users u
            WHERE u.branch_id = :branchId AND u.is_connect = true
              AND DATE(u.updated_at) = :today
              AND u.deleted_at IS NULL
            ORDER BY u.updated_at DESC
            """, nativeQuery = true)
    List<DashboardProjection.ConnectedUser> findConnectedUsersByBranchId(@Param("branchId") UUID branchId,
                                                                        @Param("today") LocalDate today);

    /**
     * Les signataires possibles d'un compte rendu : les porteurs du rôle « docteur ».
     *
     * <p>Existe parce que {@code GET /users} exige {@code edit-users}, permission
     * qu'aucun médecin n'a — leur rôle ne porte que des droits sur les comptes
     * rendus. Le sélecteur de signataire recevait donc un 403 silencieux et
     * s'affichait vide, pour tous les médecins.</p>
     *
     * <p>Les comptes inactifs sont inclus : trois des cinq docteurs le sont, et
     * ils ont signé 9 278 comptes rendus. Les écarter viderait le champ sur tous
     * ces dossiers. L'appelant reçoit l'état et le signale.</p>
     */
    @Query("""
            SELECT u FROM User u JOIN u.roles r
            WHERE r.slug = 'docteur' AND u.deletedAt IS NULL
            ORDER BY u.lastname, u.firstname
            """)
    List<User> findSignataires();

    /**
     * Les personnes qui exercent au soin : médecins et techniciens.
     *
     * <p>Ce sont elles qui figurent dans le fil de chaque dossier, qu'elles y
     * soient affectées ou non. La discussion porte des échanges cliniques, et
     * un technicien qui n'a pas composé le lot peut avoir la lame sous les
     * yeux — l'y faire entrer par une affectation serait un détour.</p>
     *
     * <p>Les comptes désactivés sont écartés, à la différence de
     * {@link #findSignataires()} : là il s'agissait de nommer qui avait signé
     * autrefois, ici de désigner qui l'on peut interpeller aujourd'hui.</p>
     */
    @Query("""
            SELECT DISTINCT u FROM User u JOIN u.roles r
            WHERE r.slug IN ('docteur', 'laborantin')
              AND u.deletedAt IS NULL AND u.isActive = true
            ORDER BY u.lastname, u.firstname
            """)
    List<User> findMetiersDuSoin();

    /** Secrets TOTP sans la marque de chiffrement (lot 11, rattrapage au démarrage). */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT id, two_factor_secret FROM users
            WHERE two_factor_secret IS NOT NULL AND two_factor_secret NOT LIKE 'v1:%'
            """, nativeQuery = true)
    List<Object[]> secretsTotpEnClair();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE users SET two_factor_secret = :valeur WHERE id = :id", nativeQuery = true)
    void remplacerSecretTotp(@org.springframework.data.repository.query.Param("id") UUID id,
                             @org.springframework.data.repository.query.Param("valeur") String valeur);
}

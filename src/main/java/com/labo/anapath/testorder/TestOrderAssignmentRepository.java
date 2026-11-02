package com.labo.anapath.testorder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestOrderAssignmentRepository extends JpaRepository<TestOrderAssignment, UUID> {

    /** Dans l'agence donnée seulement : un identifiant d'ailleurs est introuvable. */
    Optional<TestOrderAssignment> findByIdAndBranchId(UUID id, UUID branchId);

    /** Les lots d'un médecin pour un jour, dans une agence — l'assistant y ajoute ses affectations. */
    java.util.List<TestOrderAssignment> findByUserIdAndBranchIdAndDate(UUID userId, UUID branchId,
                                                                    java.time.LocalDate date);

    long countByBranchId(UUID branchId);

    /**
     * La plus haute séquence atteinte cette année, lue dans les codes eux-mêmes.
     *
     * <p>Numériquement, et non par tri texte : « 0009 » précède « 0010 », ce
     * qu'un ordre alphabétique respecte tant que la largeur est fixe mais qui
     * cède dès qu'un code hérité en sort.</p>
     *
     * <p>L'année vient du <b>code</b>, pas de {@code created_at} : une
     * affectation saisie le 2 janvier pour la veille garde son numéro de
     * l'année écoulée.</p>
     *
     * <p>Les lignes supprimées comptent. C'est délibéré : leur numéro a été
     * porté sur un bordereau, le réattribuer créerait deux affectations
     * homonymes dans les archives.</p>
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(regexp_replace(a.code, '^.*-', '') AS INTEGER)), 0)
            FROM test_order_assignments a
            WHERE a.branch_id = :branchId
              AND a.code ~ (:yearToken || '-[0-9]+$')
            """, nativeQuery = true)
    int findMaxSequenceForYear(@Param("branchId") UUID branchId,
                               @Param("yearToken") String yearToken);

    /** Y compris les lignes supprimées : un code retiré reste pris. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM test_order_assignments WHERE code = :code)",
            nativeQuery = true)
    boolean existsByCodeIncludingDeleted(@Param("code") String code);

    @Query("SELECT DISTINCT a FROM TestOrderAssignment a JOIN a.details d JOIN d.testOrder o JOIN o.typeOrder ty " +
           "WHERE a.branchId = :branchId AND ty.slug IN ('histologie','cytologie','biopsie','pièce-opératoire') " +
           "ORDER BY a.createdAt DESC")
    Page<TestOrderAssignment> findHistoCyto(@Param("branchId") UUID branchId, Pageable pageable);

    @Query("SELECT DISTINCT a FROM TestOrderAssignment a JOIN a.details d JOIN d.testOrder o JOIN o.typeOrder ty " +
           "WHERE a.branchId = :branchId AND ty.slug IN ('immuno-interne','immuno-exterme') " +
           "ORDER BY a.createdAt DESC")
    Page<TestOrderAssignment> findImmuno(@Param("branchId") UUID branchId, Pageable pageable);

    /**
     * Les lots d'une discipline autre que l'anatomie pathologique.
     *
     * <p>Un bon de biologie n'a pas de type de bon : {@link #findHistoCyto} le
     * perd dans sa jointure sur {@code typeOrder}, et c'est voulu — la liste
     * d'anatomie pathologique reste celle d'avant. Ici, on ne lit que la
     * discipline des demandes. Un lot ne mêlant pas les disciplines, une seule
     * de ses demandes suffit à le classer. Comme pour les autres listes, un lot
     * encore vide n'apparaît pas.</p>
     */
    @Query("SELECT DISTINCT a FROM TestOrderAssignment a JOIN a.details d JOIN d.testOrder o " +
           "WHERE a.branchId = :branchId AND o.discipline = :discipline " +
           "ORDER BY a.createdAt DESC")
    Page<TestOrderAssignment> findByDiscipline(@Param("branchId") UUID branchId,
                                               @Param("discipline") com.labo.anapath.common.Discipline discipline,
                                               Pageable pageable);
}

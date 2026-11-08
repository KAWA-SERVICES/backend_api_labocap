package com.labo.anapath.testorder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestOrderAssignmentDetailRepository
        extends JpaRepository<TestOrderAssignmentDetail, UUID>,
                org.springframework.data.jpa.repository.JpaSpecificationExecutor<TestOrderAssignmentDetail> {

    /** Dans l'agence donnée seulement : un identifiant d'ailleurs est introuvable. */
    Optional<TestOrderAssignmentDetail> findByIdAndBranchId(UUID id, UUID branchId);

    /**
     * Les affectations vivantes d'une demande, la plus récente en tête.
     *
     * <p>Rend une liste là où le bon sens dirait « au plus une ». C'est
     * délibéré : la relation n'a jamais été contrainte en base, et la requête
     * qui rendait un {@code Optional} levait une erreur de cardinalité sur
     * toute demande figurant dans deux lots — un incident que rien dans le code
     * n'annonçait. Une liste ne peut pas échouer ainsi ; l'appelant prend la
     * première.</p>
     */
    @Query("""
            SELECT d FROM TestOrderAssignmentDetail d
            JOIN FETCH d.testOrderAssignment a
            LEFT JOIN FETCH a.user
            WHERE d.testOrder.id = :testOrderId
              AND d.deletedAt IS NULL
              AND a.deletedAt IS NULL
              AND d.remplaceeLe IS NULL
            ORDER BY d.createdAt DESC
            """)
    List<TestOrderAssignmentDetail> affectationsCourantes(@Param("testOrderId") UUID testOrderId);

    /**
     * L'affectation qui vaut aujourd'hui, s'il y en a une : celle du titulaire.
     *
     * <p>Une ligne « pour avis » est courante elle aussi, et souvent plus
     * récente ; la prendre ferait passer le médecin consulté pour celui qui a
     * le dossier. Elle ne sert qu'à défaut de titulaire.</p>
     */
    default Optional<TestOrderAssignmentDetail> findByTestOrderId(UUID testOrderId) {
        List<TestOrderAssignmentDetail> courantes = affectationsCourantes(testOrderId);
        return courantes.stream().filter(d -> !d.isPourAvis()).findFirst()
                .or(() -> courantes.stream().findFirst());
    }

    /**
     * Toute la suite des affectations d'une demande, de la première à la
     * dernière.
     *
     * <p>Dans l'ordre où elles ont été décidées : c'est cet ordre qui répond à
     * « à qui était-ce confié au départ », question qu'on pose justement quand
     * le dossier a changé de mains plusieurs fois.</p>
     */
    @Query("""
            SELECT d FROM TestOrderAssignmentDetail d
            JOIN FETCH d.testOrderAssignment a
            LEFT JOIN FETCH a.user
            WHERE d.testOrder.id = :testOrderId
              AND d.deletedAt IS NULL
            ORDER BY d.createdAt ASC
            """)
    List<TestOrderAssignmentDetail> historiqueDe(@Param("testOrderId") UUID testOrderId);

    /**
     * Nom de la personne affectée, pour un lot de bons d'examen.
     * <p>
     * Chargement groupé : la liste des demandes résout une page entière en une
     * requête, au lieu d'une par ligne. C'est le motif déjà employé pour les
     * comptes rendus et les factures dans {@code TestOrderServiceImpl.findAll}.
     * </p>
     * <p>
     * {@code DISTINCT ON} ne retient qu'une affectation par bon : celle qui
     * vaut. La relation n'est pas contrainte, et une demande réaffectée en
     * porte plusieurs. Sans ce filtre, le bon apparaîtrait deux fois dans la
     * correspondance et le nom retenu dépendrait de l'ordre de parcours.
     * </p>
     *
     * @param testOrderIds identifiants des bons de la page courante
     * @return lignes {@code [testOrderId, nom complet]}
     */
    @Query(value = """
            SELECT DISTINCT ON (d.test_order_id)
                   d.test_order_id,
                   -- Nom puis prénoms, comme partout ailleurs. Cette requête
                   -- composait dans l'autre sens : la même personne changeait
                   -- d'ordre selon l'écran qui l'affichait.
                   TRIM(COALESCE(u.lastname, '') || ' ' || COALESCE(u.firstname, ''))
            FROM test_order_assignment_details d
            JOIN test_order_assignments a ON a.id = d.test_order_assignment_id
            JOIN users u ON u.id = a.user_id
            WHERE d.test_order_id IN (:testOrderIds)
              AND d.deleted_at IS NULL
              AND a.deleted_at IS NULL
            ORDER BY d.test_order_id,
                     -- L'affectation qui vaut passe devant celles qu'elle a
                     -- remplacées, quelle que soit la date des lots : un lot
                     -- ancien peut recevoir une demande reprise à un lot récent.
                     (d.remplacee_le IS NULL) DESC,
                     -- Parmi les courantes, le titulaire avant le médecin
                     -- consulté pour avis.
                     d.pour_avis ASC,
                     a.created_at DESC
            """, nativeQuery = true)
    List<Object[]> findAssignedUserNames(@Param("testOrderIds") List<UUID> testOrderIds);

    /**
     * La file de travail d'un médecin : toutes ses demandes, tous lots
     * confondus.
     *
     * <p>Une seule liste plate, comme la maquette l'exige : le médecin ne
     * navigue pas de lot en lot, il traite des dossiers. Le code du lot reste
     * rappelé sur chaque ligne pour qu'il sache d'où vient celui qu'il ouvre.</p>
     *
     * <p>Triée par ancienneté, du plus vieux au plus récent — c'est l'ordre
     * dans lequel on doit les traiter, et le mettre à l'envers ferait vieillir
     * en silence les dossiers du bas.</p>
     *
     * <p>Une demande qu'on a confiée à un confrère disparaît de la file
     * aussitôt : le médecin qui l'avait ne la traite plus, et la lui laisser
     * l'enverrait travailler sur un dossier qui ne lui revient pas.</p>
     *
     * <p>Une demande terminée reste visible le jour même puis disparaît : la
     * retirer aussitôt ferait douter d'avoir bien enregistré ce qu'on venait de
     * faire.</p>
     */
    @Query("""
            SELECT d FROM TestOrderAssignmentDetail d
            JOIN FETCH d.testOrderAssignment a
            LEFT JOIN FETCH d.testOrder o
            WHERE a.user.id = :docteurId
              AND d.deletedAt IS NULL
              AND d.remplaceeLe IS NULL
              AND (d.docteurStatus <> 'termine' OR a.date >= :depuis)
              AND (o.id IS NULL OR o.discipline = :discipline)
            ORDER BY a.date ASC, d.createdAt ASC
            """)
    List<TestOrderAssignmentDetail> fileDuMedecin(
            @Param("docteurId") UUID docteurId,
            @Param("depuis") java.time.LocalDate depuis,
            @Param("discipline") com.labo.anapath.common.Discipline discipline);

    /**
     * La même file, bornée à une année.
     *
     * <p>Une seconde requête plutôt qu'un paramètre facultatif dans la
     * première. La forme {@code (:borne IS NULL OR colonne >= :borne)} paraît
     * économique et ne fonctionne pas : PostgreSQL ne sait pas typer un
     * paramètre qu'on ne lui présente que dans un test de nullité, et rejette
     * la requête entière — {@code could not determine data type of parameter}.
     * L'échec est à l'exécution, sur le serveur de production, et aucun test
     * hors base ne l'aurait montré.</p>
     */
    @Query("""
            SELECT d FROM TestOrderAssignmentDetail d
            JOIN FETCH d.testOrderAssignment a
            LEFT JOIN FETCH d.testOrder o
            WHERE a.user.id = :docteurId
              AND d.deletedAt IS NULL
              AND d.remplaceeLe IS NULL
              AND (d.docteurStatus <> 'termine' OR a.date >= :depuis)
              AND o.createdAt >= :debutAnnee
              AND o.createdAt < :finAnnee
              AND o.discipline = :discipline
            ORDER BY a.date ASC, d.createdAt ASC
            """)
    List<TestOrderAssignmentDetail> fileDuMedecinPourLannee(
            @Param("docteurId") UUID docteurId,
            @Param("depuis") java.time.LocalDate depuis,
            @Param("debutAnnee") java.time.LocalDateTime debutAnnee,
            @Param("finAnnee") java.time.LocalDateTime finAnnee,
            @Param("discipline") com.labo.anapath.common.Discipline discipline);

    /**
     * Combien de dossiers de la file datent d'avant l'année demandée.
     *
     * <p>Un simple nombre, parce que c'est tout ce dont l'écran a besoin pour
     * proposer « + N des années précédentes ». Les rapatrier pour les compter
     * ferait transiter, chez un médecin de production, trois mille cinq cents
     * lignes qu'on n'affiche pas.</p>
     */
    @Query("""
            SELECT COUNT(d) FROM TestOrderAssignmentDetail d
            JOIN d.testOrderAssignment a
            LEFT JOIN d.testOrder o
            WHERE a.user.id = :docteurId
              AND d.deletedAt IS NULL
              AND d.remplaceeLe IS NULL
              AND (d.docteurStatus <> 'termine' OR a.date >= :depuis)
              AND (o.createdAt IS NULL OR o.createdAt < :debutAnnee)
              AND (o.id IS NULL OR o.discipline = :discipline)
            """)
    long compterAnterieures(@Param("docteurId") UUID docteurId,
                            @Param("depuis") java.time.LocalDate depuis,
                            @Param("debutAnnee") java.time.LocalDateTime debutAnnee,
                            @Param("discipline") com.labo.anapath.common.Discipline discipline);
}

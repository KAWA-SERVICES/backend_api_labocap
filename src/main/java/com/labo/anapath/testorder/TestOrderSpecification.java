package com.labo.anapath.testorder;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabrique de {@link Specification} JPA pour le filtrage dynamique des bons d'examen.
 *
 * <p>Utilisée par {@link TestOrderRepository} (qui étend {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor})
 * pour construire des requêtes SQL dynamiques selon les critères fournis dans {@link TestOrderFilterDto}.
 * Chaque critère null est ignoré (pas de prédicat ajouté).
 *
 * <p>Cette classe est utilitaire et ne doit pas être instanciée.
 */
public class TestOrderSpecification {

    private TestOrderSpecification() {}

    /**
     * Construit une {@link Specification} combinant tous les critères de filtrage actifs.
     *
     * <p>Critères supportés :
     * <ul>
     *   <li>branchId : toujours appliqué (isolation multi-tenant)</li>
     *   <li>discipline : PATHOLOGY par défaut (voir {@link TestOrderFilterDto#getDiscipline()})</li>
     *   <li>status, patientId, doctorId, hospitalId, isUrgent : égalité stricte</li>
     *   <li>from / to : plage de dates sur {@code prelevementDate}</li>
     *   <li>search : recherche insensible à la casse sur le code du bon</li>
     * </ul>
     *
     * @param branchId identifiant de la branche (toujours requis)
     * @param filter   critères optionnels de filtrage
     * @return la spécification composée à passer au repository
     */
    public static Specification<TestOrder> filter(UUID branchId, TestOrderFilterDto filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            query.distinct(true);

            predicates.add(cb.equal(root.get("branchId"), branchId));

            // Discipline : sans elle, un bon de biologie apparaîtrait dans les
            // listes d'anatomie pathologique, qui ne savent pas le traiter.
            if (filter.getDiscipline() != null) {
                predicates.add(cb.equal(root.get("discipline"), filter.getDiscipline()));
            }

            if (filter.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), filter.getStatus()));
            }
            // Filtre par statut du compte rendu (sous-requête sur Report, dont les
            // supprimés sont déjà exclus par @SQLRestriction) :
            //  - "NONE" → aucune ligne Report pour ce bon
            //  - "DRAFT"/"VALIDATED"/"DELIVERED" → un Report existe avec ce statut
            if (filter.getReportStatus() != null && !filter.getReportStatus().isBlank()) {
                jakarta.persistence.criteria.Subquery<UUID> sub = query.subquery(UUID.class);
                var reportRoot = sub.from(com.labo.anapath.report.Report.class);
                sub.select(reportRoot.get("id"));
                Predicate link = cb.equal(reportRoot.get("testOrder"), root);
                if ("NONE".equalsIgnoreCase(filter.getReportStatus())) {
                    sub.where(link);
                    predicates.add(cb.not(cb.exists(sub)));
                } else {
                    com.labo.anapath.report.ReportStatus rs =
                            com.labo.anapath.report.ReportStatus.valueOf(
                                    filter.getReportStatus().toUpperCase());
                    sub.where(cb.and(link, cb.equal(reportRoot.get("status"), rs)));
                    predicates.add(cb.exists(sub));
                }
            }
            if (filter.getPatientId() != null) {
                predicates.add(cb.equal(root.get("patient").get("id"), filter.getPatientId()));
            }
            if (filter.getDoctorId() != null) {
                predicates.add(cb.equal(root.get("doctor").get("id"), filter.getDoctorId()));
            }
            if (filter.getAttribuateDoctorId() != null) {
                predicates.add(cb.equal(root.get("attribuateDoctorId"), filter.getAttribuateDoctorId()));
            }
            if (filter.getHospitalId() != null) {
                predicates.add(cb.equal(root.get("hospital").get("id"), filter.getHospitalId()));
            }
            if (filter.getIsUrgent() != null) {
                predicates.add(cb.equal(root.get("isUrgent"), filter.getIsUrgent()));
            }
            if (filter.getAnnee() != null) {
                // L'année de création, et non le préfixe du code.
                //
                // Le code dit pourtant l'année — « 26-0155 » — et c'est ce que
                // le laboratoire prononce. Mais il n'est attribué qu'à la
                // validation : les 332 demandes encore en attente n'en ont
                // aucun, et un filtre sur le code les faisait toutes
                // disparaître, quelle que soit l'année demandée. Or ce sont
                // précisément celles dont l'arriéré encombre les compteurs.
                //
                // Le relevé de production tranche : sur 19 024 dossiers portant
                // un code d'année, aucun ne contredit son année de création.
                // Les deux repères disent la même chose, et celui-ci parle
                // aussi des dossiers qui n'ont pas encore de nom.
                // Un encadrement plutôt qu'un date_part : une fonction
                // appliquée à la colonne interdit à tout index de servir, et
                // cette table en compte vingt mille.
                java.time.LocalDateTime debut =
                        java.time.LocalDate.of(filter.getAnnee(), 1, 1).atStartOfDay();
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), debut));
                predicates.add(cb.lessThan(
                        root.get("createdAt"), debut.plusYears(1)));
            }
            if (Boolean.TRUE.equals(filter.getEnCours())) {
                predicates.add(root.get("status").in(
                        TestOrderStatus.DELIVERED, TestOrderStatus.CANCELLED).not());
            }
            if (filter.getFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("prelevementDate"), filter.getFrom()));
            }
            if (filter.getTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("prelevementDate"), filter.getTo()));
            }
            if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
                String pattern = "%" + filter.getSearch().toLowerCase() + "%";
                // Recherche globale : code du bon, patient, et NOM DES EXAMENS
                // demandés. Jointures LEFT pour ne pas exclure un bon sans
                // patient ou sans ligne d'examen dont le code correspondrait.
                var patientJoin = root.join("patient", jakarta.persistence.criteria.JoinType.LEFT);
                var detailJoin = root.join("details", jakarta.persistence.criteria.JoinType.LEFT);

                Predicate codeMatch = like(cb, root.get("code"), pattern);
                Predicate firstMatch = like(cb, patientJoin.get("firstname"), pattern);
                Predicate lastMatch = like(cb, patientJoin.get("lastname"), pattern);
                // Nom et prénom saisis d'un trait — « AHOSSI Jean » ne
                // correspond à aucune des deux colonnes prises isolément. Les
                // deux ordres, les données étant inversées en base.
                Predicate nomComplet = like(cb, cb.concat(cb.concat(
                        cb.coalesce(patientJoin.get("firstname"), ""), " "),
                        cb.coalesce(patientJoin.<String>get("lastname"), "")), pattern);
                Predicate nomInverse = like(cb, cb.concat(cb.concat(
                        cb.coalesce(patientJoin.get("lastname"), ""), " "),
                        cb.coalesce(patientJoin.<String>get("firstname"), "")), pattern);
                // `testName` plutôt que `labTest.name` : c'est le libellé figé
                // au moment de la commande, toujours renseigné, et il survit au
                // renommage ou à la suppression de l'examen au catalogue.
                Predicate examenMatch = like(cb, detailJoin.get("testName"), pattern);

                predicates.add(cb.or(codeMatch, firstMatch, lastMatch,
                        nomComplet, nomInverse, examenMatch));
            }
            if (filter.getContratId() != null) {
                predicates.add(cb.equal(root.get("contrat").get("id"), filter.getContratId()));
            }
            if (filter.getTypeOrderId() != null) {
                predicates.add(cb.equal(root.get("typeOrder").get("id"), filter.getTypeOrderId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Comparaison insensible à la casse <b>et aux accents</b>.
     *
     * <p>Le catalogue mêle les deux orthographes — « HYSTERECTOMIE » et
     * « HYSTÉRECTOMIE » y coexistent, héritage de la saisie libre. {@code lower}
     * seul ne replie pas les accents : chaque recherche ne verrait que sa propre
     * variante, ce qui a valu au laboratoire des examens réputés introuvables.</p>
     */
    private static Predicate like(jakarta.persistence.criteria.CriteriaBuilder cb,
                                  jakarta.persistence.criteria.Expression<String> champ,
                                  String motif) {
        return cb.like(
                cb.function("unaccent", String.class, cb.lower(cb.coalesce(champ, ""))),
                cb.function("unaccent", String.class, cb.literal(motif)));
    }

    /**
     * Les bons validés de la branche qui attendent leur macroscopie, les
     * urgents d'abord puis du plus ancien au plus récent.
     *
     * <p>Restreinte à l'anatomie pathologique, sans exception possible : la
     * macroscopie n'a pas de sens pour un bon de biologie, et la file du
     * laborantin le montrerait sinon dès sa validation. Aucun paramètre ne
     * permet d'élargir ce filtre, à dessein.</p>
     *
     * @param branchId identifiant de la branche
     * @return la spécification de la file de macroscopie
     */
    public static Specification<TestOrder> macroscopieEnAttente(UUID branchId) {
        return (root, query, cb) -> {
            query.orderBy(
                    cb.desc(root.get("isUrgent")),
                    cb.asc(root.get("createdAt")));
            return cb.and(
                    cb.equal(root.get("branchId"), branchId),
                    cb.equal(root.get("status"), TestOrderStatus.VALIDATED),
                    cb.equal(root.get("discipline"), com.labo.anapath.common.Discipline.PATHOLOGY));
        };
    }

    /**
     * Construit une {@link Specification} restreignant aux bons d'examen dont
     * le {@code typeOrder.id} est dans la liste fournie.
     *
     * <p>Si la liste est {@code null} ou vide, ne contraint pas la requête.</p>
     *
     * @param typeIds liste d'identifiants de types de bons
     * @return la spécification (toujours vraie si la liste est vide)
     */
    public static Specification<TestOrder> typeOrderIdIn(List<UUID> typeIds) {
        return (root, query, cb) -> {
            if (typeIds == null || typeIds.isEmpty()) {
                return cb.conjunction(); // pas de filtre
            }
            return root.get("typeOrder").get("id").in(typeIds);
        };
    }
}

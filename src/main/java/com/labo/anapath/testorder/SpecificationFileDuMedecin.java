package com.labo.anapath.testorder;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.report.Report;
import com.labo.anapath.report.ReportStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * La file de travail d'un médecin, telle que le serveur sait la découper.
 *
 * <h2>Pourquoi une spécification et non une requête à paramètres facultatifs</h2>
 *
 * <p>La forme {@code (:borne IS NULL OR colonne >= :borne)} paraît la façon
 * naturelle de rendre un critère optionnel en JPQL. Elle ne fonctionne pas :
 * PostgreSQL ne sait pas typer un paramètre qu'il ne rencontre que dans un test
 * de nullité, et rejette la requête entière — l'échec n'apparaît qu'à
 * l'exécution, contre une vraie base. Une spécification n'ajoute que les
 * prédicats demandés : ce qui n'est pas filtré n'existe pas dans le SQL.</p>
 */
final class SpecificationFileDuMedecin {

    private SpecificationFileDuMedecin() {}

    /**
     * @param docteurId        le médecin dont c'est la file
     * @param aujourdhui       la date du jour, pour la fenêtre des dossiers clos
     * @param joursAvantAlerte le délai au-delà duquel un dossier est en retard
     */
    static Specification<TestOrderAssignmentDetail> filtrer(
            UUID docteurId, LocalDate aujourdhui, int joursAvantAlerte,
            FiltreFileDuMedecin filtre) {
        return (racine, requete, cb) -> {
            var lot = racine.join("testOrderAssignment");
            var demande = racine.join("testOrder", JoinType.LEFT);

            List<Predicate> predicats = new ArrayList<>();
            predicats.add(cb.equal(lot.get("user").get("id"), docteurId));
            predicats.add(cb.isNull(racine.get("deletedAt")));
            // Une ligne remplacée appartient au médecin précédent.
            predicats.add(cb.isNull(racine.get("remplaceeLe")));
            // La discipline de la demande. Une ligne sans demande reste dans la
            // file comme avant : elle n'a pas de discipline à contredire.
            predicats.add(cb.or(
                    cb.isNull(demande.get("id")),
                    cb.equal(demande.get("discipline"), filtre.discipline())));
            // Les dossiers terminés restent visibles le jour même : les retirer
            // à l'instant où on les ferme ferait douter d'avoir enregistré.
            predicats.add(cb.or(
                    cb.notEqual(racine.get("docteurStatus"), DocteurStatus.TERMINE.valeur()),
                    cb.greaterThanOrEqualTo(lot.get("date"), aujourdhui)));

            if (filtre.annee() != null) {
                var debut = LocalDate.of(filtre.annee(), 1, 1).atStartOfDay();
                predicats.add(cb.greaterThanOrEqualTo(demande.get("createdAt"), debut));
                predicats.add(cb.lessThan(demande.get("createdAt"), debut.plusYears(1)));
            }
            if (renseigne(filtre.lot())) {
                predicats.add(cb.equal(lot.get("code"), filtre.lot()));
            }
            if (renseigne(filtre.docteurStatus())) {
                predicats.add(cb.equal(racine.get("docteurStatus"), filtre.docteurStatus()));
            }
            if (Boolean.TRUE.equals(filtre.exclureTermines())) {
                predicats.add(cb.notEqual(racine.get("docteurStatus"),
                        DocteurStatus.TERMINE.valeur()));
            }
            if (renseigne(filtre.statutDemande())) {
                predicats.add(cb.equal(demande.get("status"),
                        TestOrderStatus.valueOf(filtre.statutDemande())));
            }
            if (Boolean.TRUE.equals(filtre.urgents())) {
                // L'urgence se décide à l'accueil et cesse de valoir à la
                // remise : le délai qu'elle visait est alors tenu.
                predicats.add(cb.isTrue(demande.get("isUrgent")));
                predicats.add(demande.get("status").in(
                        TestOrderStatus.DELIVERED, TestOrderStatus.CANCELLED).not());
            }
            var etape = Avancement.depuis(filtre.avancement());
            if (etape != null) {
                // Une sous-requête par état plutôt qu'une jointure : une demande
                // peut porter plusieurs comptes rendus, et une jointure la
                // ferait paraître autant de fois dans la page.
                predicats.add(switch (etape) {
                    case A_TRAITER -> cb.not(cb.exists(comptesRendus(
                            requete, cb, demande, ReportStatus.PENDING_REVIEW,
                            ReportStatus.VALIDATED, ReportStatus.DELIVERED)));
                    case EN_RELECTURE -> cb.and(
                            cb.exists(comptesRendus(requete, cb, demande,
                                    ReportStatus.PENDING_REVIEW)),
                            cb.not(cb.exists(comptesRendus(requete, cb, demande,
                                    ReportStatus.VALIDATED, ReportStatus.DELIVERED))));
                    case TERMINE -> cb.exists(comptesRendus(requete, cb, demande,
                            ReportStatus.VALIDATED, ReportStatus.DELIVERED));
                });
            }
            if (Boolean.TRUE.equals(filtre.enRetard())) {
                predicats.add(cb.lessThan(demande.get("createdAt"),
                        aujourdhui.minusDays(joursAvantAlerte).atStartOfDay()));
                // Un compte rendu validé ou remis solde le retard, quel que
                // soit l'âge du dossier.
                predicats.add(cb.not(cb.exists(comptesRendus(requete, cb, demande,
                        ReportStatus.VALIDATED, ReportStatus.DELIVERED))));
            }
            if (filtre.demandes() != null) {
                // Liste vide : aucune demande ne convient. « in() » sans valeur
                // est refusé par certains pilotes, d'où la fausseté explicite.
                predicats.add(filtre.demandes().isEmpty()
                        ? cb.disjunction()
                        : demande.get("id").in(filtre.demandes()));
            }
            return cb.and(predicats.toArray(new Predicate[0]));
        };
    }

    /**
     * La file restant à faire, dans l'ordre où la traiter.
     *
     * <p>Urgents d'abord, puis en retard — du plus en retard au moins en
     * retard —, puis le reste, du lot le plus ancien au plus récent. Ordre
     * validé par le laboratoire le 2026-11-02 pour l'assistant IA.</p>
     *
     * <p>« Urgent » et « en retard » reprennent mot pour mot les critères des
     * filtres {@code urgents} et {@code enRetard} de {@link #filtrer} : un
     * dossier classé urgent ici est un dossier que le filtre « urgents »
     * montre, et inversement. Seuls les dossiers sans compte rendu validé ni
     * remis sont gardés — un dossier rendu n'est plus « à traiter ».</p>
     *
     * <p>Le tri est posé par la spécification elle-même, et seulement sur la
     * requête des lignes : la requête de comptage n'en veut pas.</p>
     */
    static Specification<TestOrderAssignmentDetail> parPriorite(
            UUID docteurId, LocalDate aujourdhui, int joursAvantAlerte, Discipline discipline) {
        FiltreFileDuMedecin filtre = new FiltreFileDuMedecin(null, null, null, null, null, null, null,
                true, null, discipline);
        Specification<TestOrderAssignmentDetail> base = filtrer(docteurId, aujourdhui, joursAvantAlerte, filtre);
        return (racine, requete, cb) -> {
            Predicate filtres = base.toPredicate(racine, requete, cb);
            var lot = racine.join("testOrderAssignment");
            var demande = racine.join("testOrder", JoinType.LEFT);
            Predicate nonRendu = cb.not(cb.exists(comptesRendus(requete, cb, demande,
                    ReportStatus.VALIDATED, ReportStatus.DELIVERED)));
            if (requete.getResultType() != Long.class && requete.getResultType() != long.class) {
                Predicate urgent = cb.and(cb.isTrue(demande.get("isUrgent")),
                        demande.get("status").in(TestOrderStatus.DELIVERED, TestOrderStatus.CANCELLED).not());
                Predicate enRetard = cb.lessThan(demande.get("createdAt"),
                        aujourdhui.minusDays(joursAvantAlerte).atStartOfDay());
                var rang = cb.<Integer>selectCase()
                        .when(urgent, 0)
                        .when(enRetard, 1)
                        .otherwise(2);
                var ageDuRetard = cb.<java.time.LocalDateTime>selectCase()
                        .when(cb.and(cb.not(urgent), enRetard), demande.<java.time.LocalDateTime>get("createdAt"))
                        .otherwise(cb.nullLiteral(java.time.LocalDateTime.class));
                requete.orderBy(
                        cb.asc(rang),
                        cb.asc(ageDuRetard),
                        cb.asc(lot.get("date")),
                        cb.asc(racine.get("createdAt")),
                        cb.asc(racine.get("id")));
            }
            return cb.and(filtres, nonRendu);
        };
    }

    /** Les comptes rendus vivants d'une demande, dans l'un de ces états. */
    private static jakarta.persistence.criteria.Subquery<Long> comptesRendus(
            jakarta.persistence.criteria.CommonAbstractCriteria requete,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Path<?> demande,
            ReportStatus... etats) {
        var sousRequete = requete.subquery(Long.class);
        var compteRendu = sousRequete.from(Report.class);
        return sousRequete.select(cb.literal(1L)).where(
                cb.equal(compteRendu.get("testOrder").get("id"), demande.get("id")),
                cb.isNull(compteRendu.get("deletedAt")),
                compteRendu.get("status").in((Object[]) etats));
    }

    private static boolean renseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }
}

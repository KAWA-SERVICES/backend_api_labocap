package com.labo.anapath.testorder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * À qui une demande d'examen a été confiée, dans l'ordre.
 *
 * <p>Une seule liste plutôt que trois champs « premier », « actuel » et
 * « historique » : les deux premiers sont des positions dans la troisième, et
 * les envoyer séparément inviterait à les faire diverger. L'écran prend le
 * premier élément et celui qui est courant.</p>
 *
 * @param demandeId identifiant de la demande
 * @param code      son code, tel qu'il figure sur le bon
 * @param etapes    les affectations successives, de la plus ancienne à la plus
 *                  récente ; vide tant que la demande n'a été confiée à personne
 */
public record HistoriqueAffectationDto(
        UUID demandeId,
        String code,
        List<EtapeAffectationDto> etapes) {

    /**
     * Une prise en charge, et ce qu'on en sait.
     *
     * @param detailId       la ligne d'affectation
     * @param affectationId  le lot dont elle fait partie
     * @param codeAffectation le code du lot — « AF26-0042 »
     * @param dateAffectation la date portée par le lot
     * @param medecinId      le médecin à qui le dossier a été confié
     * @param medecin        son nom, nom de famille en tête
     * @param confieePar     qui a composé le lot, quand on le sait
     * @param confieeLe      le moment de la prise en charge
     * @param remplaceeLe    le moment où elle a cédé la place, {@code null} si
     *                       c'est celle qui vaut aujourd'hui
     * @param statutDuMedecin où en était ce médecin quand il l'avait
     * @param pourAvis       vraie si le dossier lui a été confié pour avis
     */
    public record EtapeAffectationDto(
            UUID detailId,
            UUID affectationId,
            String codeAffectation,
            LocalDate dateAffectation,
            UUID medecinId,
            String medecin,
            String confieePar,
            LocalDateTime confieeLe,
            LocalDateTime remplaceeLe,
            String statutDuMedecin,
            boolean pourAvis) {

        /** Vraie pour l'affectation qui vaut aujourd'hui. */
        public boolean courante() {
            return remplaceeLe == null;
        }
    }
}

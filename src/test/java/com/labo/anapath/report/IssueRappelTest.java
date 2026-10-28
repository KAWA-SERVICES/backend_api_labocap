package com.labo.anapath.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IssueRappelTest {

    @Test
    @DisplayName("issue d'un appel FluidChat : seuil de 60 % pour un décroché partiel")
    void lectureDesResultats() {
        assertThat(IssueRappel.de("queued", null, 0)).isEqualTo(IssueRappel.EN_COURS);
        assertThat(IssueRappel.de("calling", null, 0)).isEqualTo(IssueRappel.EN_COURS);
        // Relance prévue : FluidChat remet l'appel en file avec le résultat de la tentative.
        assertThat(IssueRappel.de("queued", "no_answer", 0)).isEqualTo(IssueRappel.EN_COURS);
        assertThat(IssueRappel.de("answered", "completed", 100)).isEqualTo(IssueRappel.JOINT);
        assertThat(IssueRappel.de("answered", "partial", 60)).isEqualTo(IssueRappel.JOINT);
        assertThat(IssueRappel.de("answered", "partial", 59)).isEqualTo(IssueRappel.PARTIEL);
        assertThat(IssueRappel.de("no_answer", "no_answer", 0)).isEqualTo(IssueRappel.SANS_REPONSE);
        assertThat(IssueRappel.de("no_answer", "busy", 0)).isEqualTo(IssueRappel.SANS_REPONSE);
        assertThat(IssueRappel.de("failed", "unreachable", 0)).isEqualTo(IssueRappel.INJOIGNABLE);
        assertThat(IssueRappel.de("failed", "failed", 0)).isEqualTo(IssueRappel.INJOIGNABLE);
        assertThat(IssueRappel.de("canceled", null, 0)).isEqualTo(IssueRappel.ANNULE);
    }

    @Test
    @DisplayName("les requêtes SQL « à rappeler » listent exactement IssueRappel.A_RAPPELER")
    void listeSqlAlignee() {
        // Les requêtes natives (bandeau, filtre du suivi) écrivent la liste en dur :
        // ce test casse si l'enum change sans elles.
        String attendu = IssueRappel.A_RAPPELER.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        assertThat("INJOIGNABLE,PARTIEL,SANS_NUMERO,SANS_REPONSE").isEqualTo(attendu);
    }
}

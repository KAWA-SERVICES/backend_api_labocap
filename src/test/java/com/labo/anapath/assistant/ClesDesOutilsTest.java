package com.labo.anapath.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque clé qu'un outil de l'assistant renvoie est soit masquée par le
 * {@link Caviardeur}, soit déclarée ici sans risque. Une nouvelle clé qui
 * porterait un nom ou un numéro (« nomDuPrescripteur », « adresse ») fait
 * échouer ce test au lieu de partir en clair chez le fournisseur.
 */
class ClesDesOutilsTest {

    /** Ni nom, ni téléphone, ni adresse : des codes, des dates, des compteurs, des statuts, des libellés. */
    private static final Set<String> SANS_RISQUE = Set.of(
            "code", "codeDemande", "codePatient", "testOrderId", "reportId", "factureId", "medecinId",
            "statut", "statutDuMedecin", "statutCompteRendu", "typeExamen", "urgent", "urgents",
            "total", "affiches", "totalDemandes", "totalFacture", "totalPaye", "resteAPayer", "payee",
            "factureNormalisee", "modePaiement", "totalEncaisse", "echeance", "date", "annee", "dateLot", "lot", "rang", "raison",
            "creeLe", "recueLe", "prelevementDate", "signeLe", "remis", "remisLe", "remplaceeLe", "confieeLe",
            "patientPrevenu", "ouvert", "affectee", "genre", "hopital", "avancement", "enregistre",
            "aTraiter", "enCours", "enRelecture", "termines", "terminees", "enRetard", "anneesPrecedentes",
            "demandes", "factures", "comptesRendus", "affectations", "naviguer");

    @Test
    @DisplayName("Toute clé renvoyée par un outil est masquée ou déclarée sans risque")
    void clesCouvertes() throws IOException {
        Pattern cle = Pattern.compile("(?:\\.put\\(|Map\\.of\\(|,\\s*)\"([a-zA-Z]+)\"\\s*,");
        Set<String> nonCouvertes = new TreeSet<>();
        try (Stream<Path> fichiers = Files.walk(Path.of("src/main/java/com/labo/anapath"))) {
            for (Path f : fichiers.filter(p -> p.getFileName().toString().startsWith("OutilsAssistant")
                    || p.getFileName().toString().equals("OutilSignalerBesoin.java")).toList()) {
                // Les schémas JSON (blocs """) déclarent des arguments, pas des clés renvoyées.
                String source = Files.readString(f).replaceAll("(?s)\"\"\".*?\"\"\"", "");
                Matcher m = cle.matcher(source);
                while (m.find()) {
                    String k = m.group(1);
                    if (!Caviardeur.masque(k) && !SANS_RISQUE.contains(k)) nonCouvertes.add(f.getFileName() + ":" + k);
                }
            }
        }
        assertThat(nonCouvertes).as("clés ni masquées ni déclarées sans risque").isEmpty();
    }
}

package com.labo.anapath.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CaviardeurTest {

    private static final byte[] CLE = new byte[32];
    private final ObjectMapper json = JsonMapper.builder().build();

    private JsonNode resultat(String contenu) {
        return json.readTree("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":" + contenu + "}]");
    }

    @Test
    @DisplayName("Les données patient d'un résultat d'outil partent masquées, le reste en clair")
    void masqueLesClesDePatient() {
        Caviardeur c = new Caviardeur(true, CLE);
        JsonNode envoye = c.masquer(resultat("""
                [{"patientId":"6f1c2f0e-1111-2222-3333-444455556666","firstname":"Koffi","lastname":"Agossou",
                  "telephone1":"0197000000","code":"26-0155","typeExamen":"Biopsie"}]"""));
        String texte = envoye.toString();
        assertThat(texte).doesNotContain("Koffi", "Agossou", "0197000000", "6f1c2f0e");
        assertThat(texte).contains("26-0155", "Biopsie", "NOM_", "TEL_", "PATIENT_");
    }

    @Test
    @DisplayName("Les jetons sont déterministes : une table reconstruite rétablit ce qu'une autre a masqué")
    void jetonsDeterministes() {
        JsonNode clair = resultat("{\"patientName\":\"Koffi Agossou\"}");
        String jeton = new Caviardeur(true, CLE).masquer(clair).get(0).path("content").path("patientName").asString();

        Caviardeur reconstruit = new Caviardeur(true, CLE);
        reconstruit.apprendre(clair);
        assertThat(reconstruit.retablir("Le dossier de " + jeton + " est prêt.")).isEqualTo("Le dossier de Koffi Agossou est prêt.");
    }

    @Test
    @DisplayName("Un nom déjà rencontré est masqué dans le texte tapé ensuite par l'utilisateur")
    void texteLibreConnu() {
        Caviardeur c = new Caviardeur(true, CLE);
        c.apprendre(resultat("{\"lastname\":\"Agossou\"}"));
        JsonNode texte = c.masquer(json.readTree("[{\"type\":\"text\",\"text\":\"Et Agossou, il en est où ?\"}]"));
        assertThat(texte.get(0).path("text").asString()).doesNotContain("Agossou").contains("NOM_");
    }

    @Test
    @DisplayName("Les arguments d'un appel d'outil sont rétablis avant exécution")
    void retablitLesArguments() {
        Caviardeur c = new Caviardeur(true, CLE);
        JsonNode envoye = c.masquer(resultat("{\"patientId\":\"6f1c2f0e-1111-2222-3333-444455556666\"}"));
        String jeton = envoye.get(0).path("content").path("patientId").asString();
        JsonNode args = c.retablir(json.readTree("{\"patientId\":\"" + jeton + "\"}"));
        assertThat(args.path("patientId").asString()).isEqualTo("6f1c2f0e-1111-2222-3333-444455556666");
    }

    @Test
    @DisplayName("Masquage éteint : rien ne change")
    void eteint() {
        Caviardeur c = new Caviardeur(false, null);
        JsonNode clair = resultat("{\"lastname\":\"Agossou\"}");
        assertThat(c.masquer(clair)).isEqualTo(clair);
    }

    @Test
    @DisplayName("Un mot du nom, tapé en minuscules, est reconnu et masqué")
    void motDuNomSansCasse() {
        Caviardeur c = new Caviardeur(true, CLE);
        c.apprendre(resultat("{\"patientName\":\"Jean Dupont\"}"));
        String t = c.masquer(json.readTree("[{\"type\":\"text\",\"text\":\"et dupont, il a payé ?\"}]"))
                .get(0).path("text").asString();
        assertThat(t).doesNotContainIgnoringCase("dupont").contains("NOM_");
    }

    @Test
    @DisplayName("Sans la clé du serveur, le même nom ne donne pas le même jeton : pas de dictionnaire possible")
    void jetonDependDeLaCle() {
        JsonNode clair = resultat("{\"lastname\":\"Agossou\"}");
        byte[] autre = new byte[32];
        autre[0] = 1;
        String a = new Caviardeur(true, CLE).masquer(clair).toString();
        String b = new Caviardeur(true, autre).masquer(clair).toString();
        assertThat(a).isNotEqualTo(b);
        assertThat(a).containsPattern("NOM_[0-9a-f]{16}");
    }

    @Test
    @DisplayName("Masquage actif sans clé : refusé")
    void sansCle() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Caviardeur(true, null))
                .isInstanceOf(IllegalStateException.class);
    }
}

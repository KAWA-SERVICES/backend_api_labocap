package com.labo.anapath.assistant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le masquage des données patient sur le chemin du fournisseur.
 *
 * <p>Aucun nom, téléphone, courriel ni identifiant de patient ne part en clair :
 * chaque valeur devient un jeton opaque ({@code NOM_3fa2c1}), et les jetons que
 * le modèle renvoie — dans son texte ou dans les arguments d'un outil — sont
 * rétablis avant l'affichage et l'exécution.</p>
 *
 * <p>Les jetons sont <strong>déterministes</strong> — un HMAC de la valeur sous
 * une clé du serveur, dérivée de {@code SECRETS_KEY} — : la table se reconstruit à
 * l'identique en repassant l'historique en clair, sans être stockée, et un jeton
 * ne se renverse pas par dictionnaire de noms sans la clé. Huit octets
 * d'empreinte : une collision entre deux patients d'une même conversation est
 * hors de portée, et si elle survenait, le jeton est allongé plutôt que partagé. Chaque message part masqué une fois pour toutes —
 * sa forme envoyée est conservée — car le fournisseur exige que l'historique
 * déjà envoyé ne change pas d'un tour à l'autre.</p>
 *
 * <p>Ce qui est masqué se décide <em>par clé</em> dans les données structurées
 * que rendent les outils ; les outils, eux, ne projettent jamais de texte libre
 * (notes, contenu des comptes rendus, adresses). <strong>Limite connue</strong> :
 * le texte que tape l'utilisateur n'est masqué que pour les valeurs déjà
 * rencontrées dans la conversation (le nom entier et chacun de ses mots, sans
 * tenir compte de la casse) — un nom tapé avant toute recherche part en clair.
 * Il faudrait une détection d'entités nommées pour le couvrir.</p>
 */
public class Caviardeur {

    private static final Map<String, String> GENRE_PAR_CLE = Map.ofEntries(
            Map.entry("firstname", "NOM"), Map.entry("lastname", "NOM"),
            Map.entry("patientname", "NOM"), Map.entry("patientfirstname", "NOM"),
            Map.entry("patientlastname", "NOM"), Map.entry("nompatient", "NOM"),
            Map.entry("receivername", "NOM"), Map.entry("retrievername", "NOM"),
            // Le client facturé est souvent le patient lui-même.
            Map.entry("clientname", "NOM"),
            Map.entry("telephone", "TEL"), Map.entry("telephone1", "TEL"), Map.entry("telephone2", "TEL"),
            // Le personnel et les prescripteurs sont aussi des personnes.
            Map.entry("medecin", "NOM"), Map.entry("confieepar", "NOM"), Map.entry("relupar", "NOM"),
            Map.entry("validepar", "NOM"), Map.entry("prescripteur", "NOM"), Map.entry("etaitchez", "NOM"),
            // Clés génériques : une future projection qui les emploierait reste masquée.
            Map.entry("name", "NOM"), Map.entry("nom", "NOM"), Map.entry("prenom", "NOM"),
            Map.entry("fullname", "NOM"), Map.entry("nomcomplet", "NOM"),
            Map.entry("phone", "TEL"), Map.entry("mobile", "TEL"),
            Map.entry("email", "MEL"),
            Map.entry("patientid", "PATIENT"), Map.entry("codepatient", "CODEPAT"));

    /** Les valeurs trop courtes se retrouveraient partout dans le texte : on ne les remplace pas en texte libre. */
    /** Pour le test qui vérifie que chaque clé renvoyée par un outil est masquée ou déclarée sans risque. */
    static boolean masque(String cle) {
        return GENRE_PAR_CLE.containsKey(cle.toLowerCase(java.util.Locale.ROOT));
    }

    private static final int LONGUEUR_MINIMALE_EN_TEXTE = 3;

    private final boolean actif;
    private final byte[] cle;
    private final Map<String, String> versJeton = new HashMap<>();
    private final Map<String, String> versClair = new HashMap<>();

    /**
     * @param cle la clé des empreintes ; obligatoire si {@code actif} (sans elle,
     *            les jetons se renverseraient par dictionnaire)
     */
    public Caviardeur(boolean actif, byte[] cle) {
        if (actif && (cle == null || cle.length < 16)) {
            throw new IllegalStateException("Masquage sans clé de chiffrement des secrets (SECRETS_KEY).");
        }
        this.actif = actif;
        this.cle = cle;
    }

    public boolean actif() {
        return actif;
    }

    /** Apprend les valeurs d'un message déjà en clair, sans rien produire : reconstruit la table. */
    public void apprendre(JsonNode contenuClair) {
        if (actif) masquer(contenuClair);
    }

    /** La forme envoyée d'un contenu (tableau de blocs neutres) ; ne modifie pas l'entrée. */
    public JsonNode masquer(JsonNode contenu) {
        if (!actif || contenu == null) return contenu;
        return parcourir(contenu, null, true);
    }

    private JsonNode parcourir(JsonNode n, String cle, boolean blocs) {
        if (n.isObject()) {
            ObjectNode copie = JsonNodeFactory.instance.objectNode();
            String type = blocs ? n.path("type").asString() : null;
            for (var champ : n.properties()) {
                String k = champ.getKey();
                JsonNode v = champ.getValue();
                if ("text".equals(type) && "text".equals(k)) {
                    copie.put(k, remplacerConnus(v.asString()));
                } else if (blocs && ("input".equals(k) || "content".equals(k))) {
                    copie.set(k, parcourir(v, k, false));
                } else if (blocs) {
                    copie.set(k, v);
                } else {
                    copie.set(k, parcourir(v, k, false));
                }
            }
            return copie;
        }
        if (n.isArray()) {
            ArrayNode copie = JsonNodeFactory.instance.arrayNode();
            for (JsonNode e : n) copie.add(parcourir(e, cle, blocs));
            return copie;
        }
        if (!blocs && n.isString() && cle != null) {
            String genre = GENRE_PAR_CLE.get(cle.toLowerCase());
            if (genre != null && !n.asString().isBlank()) return JsonNodeFactory.instance.stringNode(jeton(n.asString(), genre));
            // Toute autre chaîne (message d'erreur, libellé) : les valeurs déjà connues y sont masquées.
            // Mots entiers seulement : un code ou un libellé ne doit pas être coupé en deux.
            if (genre == null) return JsonNodeFactory.instance.stringNode(remplacerConnus(n.asString(), true));
        }
        return n;
    }

    private String jeton(String clair, String genre) {
        String j = enregistrer(clair, genre);
        // Chaque mot d'un nom aussi : « dupont », tapé seul, doit être reconnu.
        if ("NOM".equals(genre)) {
            for (String mot : clair.split("[\\s,;'-]+")) {
                if (mot.length() >= LONGUEUR_MINIMALE_EN_TEXTE && !mot.equals(clair)) enregistrer(mot, genre);
            }
        }
        return j;
    }

    private String enregistrer(String clair, String genre) {
        String deja = versJeton.get(clair);
        if (deja != null) return deja;
        String e = empreinte(clair);
        String j = genre + "_" + e.substring(0, 16);
        // Collision : on allonge plutôt que de confondre deux patients.
        if (versClair.containsKey(j) && !versClair.get(j).equals(clair)) j = genre + "_" + e;
        versJeton.put(clair, j);
        versClair.put(j, clair);
        return j;
    }

    private String remplacerConnus(String texte) {
        return remplacerConnus(texte, false);
    }

    private String remplacerConnus(String texte, boolean motsEntiers) {
        if (texte == null || versJeton.isEmpty()) return texte;
        for (String clair : parLongueur(versJeton.keySet())) {
            if (clair.length() < LONGUEUR_MINIMALE_EN_TEXTE) continue;
            String motif = motsEntiers ? "(?<![\\p{L}\\p{N}])" + Pattern.quote(clair) + "(?![\\p{L}\\p{N}])" : Pattern.quote(clair);
            Matcher m = Pattern.compile(motif, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(texte);
            if (m.find()) texte = m.replaceAll(Matcher.quoteReplacement(versJeton.get(clair)));
        }
        return texte;
    }

    /** Rétablit les valeurs dans un texte du modèle. */
    public String retablir(String texte) {
        if (!actif || texte == null || versClair.isEmpty()) return texte;
        for (String j : parLongueur(versClair.keySet())) {
            if (texte.contains(j)) texte = texte.replace(j, versClair.get(j));
        }
        return texte;
    }

    /** Rétablit les valeurs dans les arguments d'un appel d'outil. */
    public JsonNode retablir(JsonNode n) {
        if (!actif || n == null) return n;
        if (n.isString()) return JsonNodeFactory.instance.stringNode(retablir(n.asString()));
        if (n.isObject()) {
            ObjectNode copie = JsonNodeFactory.instance.objectNode();
            n.properties().forEach(e -> copie.set(e.getKey(), retablir(e.getValue())));
            return copie;
        }
        if (n.isArray()) {
            ArrayNode copie = JsonNodeFactory.instance.arrayNode();
            n.forEach(e -> copie.add(retablir(e)));
            return copie;
        }
        return n;
    }

    /** Les plus longues d'abord : « NOM_ab12 » ne doit pas mordre sur un jeton qui le prolonge. */
    private static Iterable<String> parLongueur(Set<String> valeurs) {
        return valeurs.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private String empreinte(String valeur) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cle, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(valeur.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.labo.anapath.report;

import com.labo.anapath.common.notification.FluidChatProperties;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Réglages du rappel automatique des patients, modifiables dans Paramètres
 * ({@code setting_apps}).
 *
 * <p>Comme pour {@code SmsTemplates}, le défaut reste dans le code : une clé
 * absente ou mal saisie ne doit ni bloquer les rappels ni faire sonner un
 * téléphone un dimanche. Lus globalement, sans agence : la tâche planifiée tourne
 * hors requête, et il n'y a qu'un téléphone passerelle FluidChat.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReglagesDeRappel {

    static final String CLE_DELAI_HEURES = "rappel_delai_heures";
    static final String CLE_HEURE_DEBUT = "rappel_heure_debut";
    static final String CLE_HEURE_FIN = "rappel_heure_fin";
    /** Jours d'appel, convention FluidChat : 0 = dimanche … 6 = samedi. */
    static final String CLE_JOURS = "rappel_jours";
    static final String CLE_AUDIO_FR = "fluidchat_audio_fr";
    static final String CLE_AUDIO_FON = "fluidchat_audio_fon";
    static final String CLE_AUDIO_EN = "fluidchat_audio_en";
    static final String CLE_AUDIO_BIP = "fluidchat_audio_bip";

    /** Plage réglementaire (ARCE) par défaut : du lundi au vendredi, 8h–18h. */
    static final int DEFAUT_DELAI_HEURES = 24;
    static final String DEFAUT_HEURE_DEBUT = "08:00";
    static final String DEFAUT_HEURE_FIN = "18:00";
    static final String DEFAUT_JOURS = "1,2,3,4,5";

    /** Repère de {@link #manquants()} : l'adresse ou la clé FluidChat n'est pas dans l'environnement. */
    public static final String MANQUE_FLUIDCHAT = "fluidchat_api";

    private final SettingAppRepository settingAppRepository;
    private final FluidChatProperties fluidChatProperties;

    /** Heures entre la validation du compte rendu et l'avis au patient. */
    public int delaiHeures() {
        try {
            return Math.max(0, Integer.parseInt(valeur(CLE_DELAI_HEURES, String.valueOf(DEFAUT_DELAI_HEURES)).trim()));
        } catch (NumberFormatException e) {
            log.warn("{} illisible : défaut {}h", CLE_DELAI_HEURES, DEFAUT_DELAI_HEURES);
            return DEFAUT_DELAI_HEURES;
        }
    }

    /** Plage où un appel peut sonner ; un réglage illisible retombe sur la plage réglementaire. */
    public Plage plage() {
        try {
            Set<Integer> jours = Arrays.stream(valeur(CLE_JOURS, DEFAUT_JOURS).split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                    .collect(Collectors.toSet());
            return new Plage(LocalTime.parse(valeur(CLE_HEURE_DEBUT, DEFAUT_HEURE_DEBUT).trim()),
                    LocalTime.parse(valeur(CLE_HEURE_FIN, DEFAUT_HEURE_FIN).trim()), jours);
        } catch (RuntimeException e) {
            log.warn("Plage de rappel illisible ({}) : plage réglementaire appliquée", e.getMessage());
            return Plage.REGLEMENTAIRE;
        }
    }

    /**
     * Lien mp3 (ou {@code custom/<id>} FluidChat) dans la langue du patient,
     * le français sans langue connue ; nul si ce lien n'est pas renseigné : aucun
     * appel ne part alors, et le bandeau de {@link #manquants()} le signale.
     */
    public String audio(String langue) {
        return valeur(cleAudio(langue), null);
    }

    private static String cleAudio(String langue) {
        return switch (langue == null ? "" : langue.toLowerCase()) {
            case "fon" -> CLE_AUDIO_FON;
            case "anglais" -> CLE_AUDIO_EN;
            default -> CLE_AUDIO_FR;
        };
    }

    /**
     * Ce qui empêche des appels de partir : clés audio vides et, en tête,
     * {@link #MANQUE_FLUIDCHAT} si FluidChat n'est pas configuré. Vide : tout est prêt.
     */
    public List<String> manquants() {
        List<String> manquants = new ArrayList<>();
        if (!fluidChatProperties.isUsable()) {
            manquants.add(MANQUE_FLUIDCHAT);
        }
        for (String cle : List.of(CLE_AUDIO_FR, CLE_AUDIO_FON, CLE_AUDIO_EN, CLE_AUDIO_BIP)) {
            if (valeur(cle, null) == null) {
                manquants.add(cle);
            }
        }
        return manquants;
    }

    /** Lien de l'audio du bip de la facture ; nul : aucun bip ne part. */
    public String audioBip() {
        return valeur(CLE_AUDIO_BIP, null);
    }

    /** Dernière valeur enregistrée de la clé ; vidée dans Paramètres, elle vaut le défaut. */
    private String valeur(String cle, String defaut) {
        return settingAppRepository.findFirstByKeyOrderByUpdatedAtDesc(cle)
                .map(SettingApp::getValue)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(defaut);
    }

    /**
     * Heures et jours où un appel automatique peut sonner, à l'heure du laboratoire.
     *
     * @param jours 0 = dimanche … 6 = samedi, comme FluidChat
     */
    public record Plage(LocalTime debut, LocalTime fin, Set<Integer> jours) {

        static final Plage REGLEMENTAIRE =
                new Plage(LocalTime.of(8, 0), LocalTime.of(18, 0), Set.of(1, 2, 3, 4, 5));

        /** Vrai si l'instant tombe dans la plage (bornes comprises : 8h00 et 18h00). */
        public boolean contient(ZonedDateTime instant) {
            ZonedDateTime local = instant.withZoneSameInstant(NotificationServiceImpl.FUSEAU_LABORATOIRE);
            int jour = local.getDayOfWeek().getValue() % 7; // ISO lundi=1 … dimanche=7 → 0
            LocalTime heure = local.toLocalTime();
            return jours.contains(jour) && !heure.isBefore(debut) && !heure.isAfter(fin);
        }

        /** Le {@code call_window} de FluidChat. */
        public Map<String, Object> versCallWindow() {
            ZoneId fuseau = NotificationServiceImpl.FUSEAU_LABORATOIRE;
            return Map.of("start", debut.toString(), "end", fin.toString(),
                    "days", jours.stream().sorted().toList(), "timezone", fuseau.getId());
        }
    }
}

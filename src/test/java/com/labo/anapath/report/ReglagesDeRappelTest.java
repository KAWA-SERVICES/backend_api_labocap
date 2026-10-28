package com.labo.anapath.report;

import com.labo.anapath.common.notification.FluidChatProperties;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReglagesDeRappelTest {

    private final SettingAppRepository settings = mock(SettingAppRepository.class);
    private final FluidChatProperties fluidChat = new FluidChatProperties();
    private final ReglagesDeRappel reglages = new ReglagesDeRappel(settings, fluidChat);

    /** Dernière valeur enregistrée de chaque clé ; une clé absente n'a jamais été saisie. */
    private void parametres(Map<String, String> valeurs) {
        when(settings.findFirstByKeyOrderByUpdatedAtDesc(anyString())).thenAnswer(i -> {
            String valeur = valeurs.get(i.<String>getArgument(0));
            if (valeur == null) {
                return Optional.empty();
            }
            SettingApp s = new SettingApp();
            s.setValue(valeur);
            return Optional.of(s);
        });
    }

    @Test
    @DisplayName("audio : le lien de la langue du patient, rien si ce lien est vide — pas de repli sur le français")
    void audioParLangue() {
        parametres(Map.of("fluidchat_audio_fr", " https://caap.bj/fr.mp3 ", "fluidchat_audio_fon", ""));

        assertThat(reglages.audio("français")).isEqualTo("https://caap.bj/fr.mp3");
        assertThat(reglages.audio(null)).isEqualTo("https://caap.bj/fr.mp3");
        assertThat(reglages.audio("fon")).isNull();
        assertThat(reglages.audio("anglais")).isNull();
        assertThat(reglages.audioBip()).isNull();
    }

    @Test
    @DisplayName("manquants : FluidChat non configuré en tête, puis chaque lien vide ; rien quand tout est prêt")
    void manquants() {
        parametres(Map.of("fluidchat_audio_fr", "https://caap.bj/fr.mp3"));

        assertThat(reglages.manquants()).containsExactly(ReglagesDeRappel.MANQUE_FLUIDCHAT,
                "fluidchat_audio_fon", "fluidchat_audio_en", "fluidchat_audio_bip");

        fluidChat.setBaseUrl("https://demo-fluidchat.kawaservices.com");
        fluidChat.setApiKey("fc_live_test");
        parametres(Map.of("fluidchat_audio_fr", "a", "fluidchat_audio_fon", "b",
                "fluidchat_audio_en", "c", "fluidchat_audio_bip", "d"));

        assertThat(reglages.manquants()).isEmpty();
    }
}

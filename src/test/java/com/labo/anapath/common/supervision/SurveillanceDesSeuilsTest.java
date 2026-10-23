package com.labo.anapath.common.supervision;

import com.labo.anapath.common.email.EmailService;
import com.labo.anapath.setting.SettingApp;
import com.labo.anapath.setting.SettingAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurveillanceDesSeuilsTest {

    private static final String PRESTATAIRE = "ops@prestataire.bj";

    @Mock
    private EmailService emailService;
    @Mock
    private SettingAppRepository settingAppRepository;

    private final CompteurDAlertes compteur = new CompteurDAlertes();
    private SurveillanceDesSeuils surveillance;

    @BeforeEach
    void monter() {
        // Un chemin de stockage inexistant : le disque n'entre pas dans ces
        // cas-ci. La racine « / » reste mesurée ; ses alertes portent le mot
        // « Disque » et sont écartées des vérifications ci-dessous.
        surveillance = new SurveillanceDesSeuils(compteur, emailService, settingAppRepository,
                PRESTATAIRE, "/chemin/qui/n/existe/pas");
    }

    private void adminMails(String valeur) {
        SettingApp reglage = new SettingApp();
        reglage.setValue(valeur);
        when(settingAppRepository.findByKeyInOrderByCreatedAtAsc(List.of("admin_mails")))
                .thenReturn(List.of(reglage));
    }

    private static String horsDisque(String sujet) {
        return sujet.startsWith("[Labo] Disque") ? "" : sujet;
    }

    @Test
    @DisplayName("25 échecs en 10 minutes : un courriel aux administrateurs et au prestataire")
    void rafaleDeConnexions() {
        adminMails("admin@labo.bj|dsi@labo.bj");
        for (int i = 0; i < 25; i++) compteur.echecDeConnexion();

        surveillance.evaluer();

        verify(emailService, times(1)).sendAlerteSupervision(
                eq(List.of("admin@labo.bj", "dsi@labo.bj", PRESTATAIRE)),
                argThat(s -> s.contains("25 connexions ratées")), any());
    }

    @Test
    @DisplayName("6 verrouillages en 1 heure : courriel")
    void verrouillages() {
        adminMails("admin@labo.bj");
        for (int i = 0; i < 6; i++) compteur.verrouillage();

        surveillance.evaluer();

        verify(emailService).sendAlerteSupervision(any(), argThat(s -> s.contains("6 comptes verrouillés")), any());
    }

    @Test
    @DisplayName("11 erreurs 5xx en 5 minutes : courriel au prestataire seul")
    void erreursServeur() {
        for (int i = 0; i < 11; i++) compteur.erreurServeur();

        surveillance.evaluer();

        verify(emailService).sendAlerteSupervision(eq(List.of(PRESTATAIRE)),
                argThat(s -> s.contains("11 erreurs serveur")), any());
    }

    @Test
    @DisplayName("sous les seuils : aucun courriel")
    void sousLesSeuils() {
        for (int i = 0; i < 20; i++) compteur.echecDeConnexion();
        for (int i = 0; i < 5; i++) compteur.verrouillage();
        for (int i = 0; i < 10; i++) compteur.erreurServeur();

        surveillance.evaluer();

        verify(emailService, never()).sendAlerteSupervision(any(), argThat(s -> !horsDisque(s).isEmpty()), any());
    }

    @Test
    @DisplayName("deuxième évaluation dans l'heure : pas de second courriel")
    void pasDInondation() {
        for (int i = 0; i < 11; i++) compteur.erreurServeur();

        surveillance.evaluer();
        surveillance.evaluer();

        verify(emailService, times(1)).sendAlerteSupervision(any(), argThat(s -> s.contains("erreurs serveur")), any());
    }

    @Test
    @DisplayName("60 liens de facture refusés en 10 minutes : courriel au prestataire seul")
    void essaiDeJetons() {
        for (int i = 0; i < 60; i++) compteur.lienDeFactureRefuse();

        surveillance.evaluer();

        verify(emailService).sendAlerteSupervision(eq(List.of(PRESTATAIRE)),
                argThat(s -> s.contains("60 liens de facture refusés")), any());
    }

    @Test
    @DisplayName("quelques liens expirés : pas d'alerte")
    void quelquesLiensExpires_silence() {
        for (int i = 0; i < 5; i++) compteur.lienDeFactureRefuse();

        surveillance.evaluer();

        verify(emailService, never()).sendAlerteSupervision(any(),
                argThat(s -> s.contains("liens de facture")), any());
    }
}

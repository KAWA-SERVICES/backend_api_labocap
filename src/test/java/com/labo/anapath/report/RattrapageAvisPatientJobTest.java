package com.labo.anapath.report;

import com.labo.anapath.common.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RattrapageAvisPatientJobTest {

    @Mock private ReportRepository reportRepository;
    @Mock private NotificationService notificationService;
    @Mock private ReglagesDeRappel reglagesDeRappel;
    @Mock private AppelByReportRepository appelByReportRepository;

    @InjectMocks private RattrapageAvisPatientJob job;

    @BeforeEach
    void delaiParDefaut() {
        when(reglagesDeRappel.delaiHeures()).thenReturn(24);
    }

    private Report compteRendu() {
        Report report = new Report();
        report.setId(UUID.randomUUID());
        return report;
    }

    @Test
    @DisplayName("chaque avis dû est envoyé, et le lot est plafonné à 100")
    void envoieLesAvisDus() {
        Report premier = compteRendu();
        Report second = compteRendu();
        when(reportRepository.findAvisPatientEnAttente(any(), any(), any())).thenReturn(List.of(premier, second));
        when(notificationService.notifyPatient(any(), eq(null)))
                .thenReturn(new NotifyResponseDto("CALL", null, "57", "SMS envoyé et appel programmé"));

        assertThat(job.rattraperAvisEnAttente()).isEqualTo(2);

        verify(notificationService).notifyPatient(premier.getId(), null);
        verify(notificationService).notifyPatient(second.getId(), null);
        ArgumentCaptor<Pageable> lot = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findAvisPatientEnAttente(any(), any(), lot.capture());
        assertThat(lot.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("seuls les comptes rendus validés depuis X heures sont dus, sur une fenêtre de trois jours")
    void delaiEtFenetre() {
        when(reportRepository.findAvisPatientEnAttente(any(), any(), any())).thenReturn(List.of());

        job.rattraperAvisEnAttente();

        ArgumentCaptor<LocalDateTime> depuis = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> avant = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportRepository).findAvisPatientEnAttente(depuis.capture(), avant.capture(), any());
        LocalDateTime ilYA24h = LocalDateTime.now().minusHours(24);
        assertThat(avant.getValue()).isBetween(ilYA24h.minusMinutes(1), ilYA24h.plusMinutes(1));
        // Sans cette borne, une panne de plusieurs jours ferait partir un arriéré
        // entier d'appels à la première exécution.
        assertThat(depuis.getValue()).isEqualTo(avant.getValue().minusDays(3));
    }

    @Test
    @DisplayName("un avis en échec n'empêche pas les suivants de partir")
    void poursuitLeLotMalgreUnEchec() {
        Report enEchec = compteRendu();
        Report suivant = compteRendu();
        when(reportRepository.findAvisPatientEnAttente(any(), any(), any())).thenReturn(List.of(enEchec, suivant));
        when(notificationService.notifyPatient(enEchec.getId(), null)).thenThrow(new ExternalApiException("panne"));
        when(notificationService.notifyPatient(suivant.getId(), null))
                .thenReturn(new NotifyResponseDto("CALL", null, "58", "SMS envoyé et appel programmé"));

        assertThat(job.rattraperAvisEnAttente()).isEqualTo(1);
        verify(notificationService).notifyPatient(suivant.getId(), null);
    }

    @Test
    @DisplayName("hors de la plage réglementaire, la tâche n'envoie rien — pas même le SMS")
    void horsPlage_rienNePart() {
        // Plage vide : aucun jour autorisé, quel que soit le moment du test.
        when(reglagesDeRappel.plage()).thenReturn(
                new ReglagesDeRappel.Plage(LocalTime.of(8, 0), LocalTime.of(18, 0), Set.of()));

        job.rappelerLesPatients();

        verify(reportRepository, never()).findAvisPatientEnAttente(any(), any(), any());
    }

    @Test
    @DisplayName("suivi : chaque appel en cours est relu, une panne n'arrête pas les suivants")
    void suivi_relitLesAppelsEnCours() {
        AppelByReport enPanne = new AppelByReport();
        AppelByReport suivant = new AppelByReport();
        when(appelByReportRepository.findByIssueAndCreatedAtAfterOrderByCreatedAtAsc(eq(IssueRappel.EN_COURS), any(), any()))
                .thenReturn(List.of(enPanne, suivant));
        org.mockito.Mockito.doThrow(new ExternalApiException("panne")).when(notificationService).actualiser(enPanne);

        job.suivreLesAppels();

        verify(notificationService).actualiser(suivant);
    }

    @Test
    @DisplayName("Plage : lundi–vendredi 8h–18h, heure de Porto-Novo, bornes comprises")
    void plageReglementaire() {
        ReglagesDeRappel.Plage plage = ReglagesDeRappel.Plage.REGLEMENTAIRE;
        java.time.ZoneId porto = NotificationServiceImpl.FUSEAU_LABORATOIRE;
        // 2026-10-30 : un vendredi ; 2026-10-31 : un samedi.
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, porto))).isTrue();
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 30, 18, 0, 0, 0, porto))).isTrue();
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 30, 18, 1, 0, 0, porto))).isFalse();
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 30, 7, 59, 0, 0, porto))).isFalse();
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, porto))).isFalse();
        // 17h30 UTC = 18h30 à Porto-Novo : hors plage, même si l'horloge serveur dit 17h30.
        assertThat(plage.contient(ZonedDateTime.of(2026, 10, 30, 17, 30, 0, 0, java.time.ZoneOffset.UTC))).isFalse();
    }
}

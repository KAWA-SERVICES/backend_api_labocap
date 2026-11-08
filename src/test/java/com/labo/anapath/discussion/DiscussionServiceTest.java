package com.labo.anapath.discussion;

import com.labo.anapath.common.exception.BusinessException;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignmentDetailRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les règles du fil de discussion.
 *
 * <p>Trois d'entre elles échoueraient en silence. Un message vide accepté
 * remplirait le fil de lignes sans contenu que personne ne saurait retirer. Un
 * type inconnu passerait jusqu'à l'écran, qui ne saurait pas l'afficher. Et un
 * auteur qui n'aurait pas « lu » son propre message verrait un badge s'allumer
 * en écrivant — ce qui ferait douter de tous les autres badges.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscussionServiceTest {

    @Mock private DiscussionRepository discussions;
    @Mock private DiscussionMessageRepository messages;
    @Mock private DiscussionParticipantRepository participants;
    @Mock private DiscussionLectureRepository lectures;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private TestOrderAssignmentDetailRepository detailRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.labo.anapath.testorder.FileStorageService fichiers;
    @Mock private com.labo.anapath.common.storage.FichierStockeRepository rattachements;
    @Mock private com.labo.anapath.mobile.NotificationsPush notifications;
    @Mock private com.labo.anapath.mobile.MobileDeviceRepository appareils;

    @InjectMocks private DiscussionService service;

    private final UUID DEMANDE = UUID.randomUUID();
    private final UUID BRANCHE = UUID.randomUUID();
    private final UUID AUTEUR = UUID.randomUUID();
    private final UUID AUTRUI = UUID.randomUUID();
    private Discussion fil;

    @BeforeEach
    void poser() {
        TestOrder demande = new TestOrder();
        demande.setCode("26-0155");
        // L'identifiant compte : `ouvrirOuCreer` cherche le fil par lui, et un
        // nul ferait manquer le stub — le service repartirait alors sur un fil
        // absent, ce qui n'arrive jamais en vrai.
        poserId(demande, DEMANDE);
        when(testOrderRepository.findByIdAndBranchId(DEMANDE, BRANCHE))
                .thenReturn(Optional.of(demande));

        fil = new Discussion(DEMANDE, BRANCHE);
        when(discussions.findByTestOrderId(DEMANDE)).thenReturn(Optional.of(fil));

        User auteur = new User();
        auteur.setLastname("AGBO");
        auteur.setFirstname("Marc");
        auteur.setRoles(java.util.List.of(role("docteur")));
        poserId(auteur, AUTEUR);
        when(userRepository.findById(AUTEUR)).thenReturn(Optional.of(auteur));
        when(userRepository.findAllById(any())).thenReturn(List.of(auteur));

        when(detailRepository.affectationsCourantes(DEMANDE)).thenReturn(List.of());
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        // Un dépôt rend ce qu'on lui confie. Le laisser rendre `null` glissait
        // un participant nul dans le fil, ce qui n'arrive jamais en vrai.
        when(participants.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** Un rôle à un seul champ utile : son slug. */
    private static com.labo.anapath.role.Role role(String slug) {
        var r = new com.labo.anapath.role.Role();
        r.setSlug(slug);
        return r;
    }

    /** Pose l'identifiant d'une entité auditée, que rien n'expose en écriture. */
    private static void poserId(Object entite, UUID id) {
        try {
            var champ = entite.getClass().getSuperclass().getDeclaredField("id");
            champ.setAccessible(true);
            champ.set(entite, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("un message vide ne s'envoie pas")
    void messageVide() {
        for (String contenu : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.poster(DEMANDE,
                    new DiscussionDtos.NouveauMessage("texte", contenu, null),
                    AUTEUR, BRANCHE))
                    .isInstanceOf(BusinessException.class);
        }
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("un type inconnu est refusé plutôt que servi à l'écran")
    void typeInconnu() {
        assertThatThrownBy(() -> service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("video", "bonjour", null),
                AUTEUR, BRANCHE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("video");
    }

    @Test
    @DisplayName("les trois types prévus passent")
    void lesTroisTypes() {
        for (String type : List.of("texte", "photo", "audio")) {
            var dto = service.poster(DEMANDE,
                    new DiscussionDtos.NouveauMessage(type, "quelque chose", null),
                    AUTEUR, BRANCHE);
            assertThat(dto.type()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("l'auteur a lu son propre message")
    void lAuteurALuSonMessage() {
        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Lame reçue.", null),
                AUTEUR, BRANCHE);

        // Sans cette ligne, un badge s'allumerait chez celui qui vient
        // d'écrire — et ferait douter de tous les autres.
        ArgumentCaptor<DiscussionLecture> capte =
                ArgumentCaptor.forClass(DiscussionLecture.class);
        verify(lectures).save(capte.capture());
        assertThat(capte.getValue().getUserId()).isEqualTo(AUTEUR);
    }

    @Test
    @DisplayName("le type par défaut est le texte")
    void typeParDefaut() {
        var dto = service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage(null, "bonjour", null),
                AUTEUR, BRANCHE);

        assertThat(dto.type()).isEqualTo("texte");
    }

    /** Un entête MP4 : la boîte `ftyp` au quatrième octet. */
    private static byte[] enteteMp4() {
        byte[] t = new byte[32];
        t[4] = 'f'; t[5] = 't'; t[6] = 'y'; t[7] = 'p';
        return t;
    }

    @Test
    @DisplayName("un fichier qui n'est pas de l'audio est refusé, même annoncé comme tel")
    void formatRefuse() throws Exception {
        // Le format se lit dans le fichier, pas dans ce que le client annonce.
        // Un type déclaré ne protège de rien : il suffit de le changer.
        var fichier = new org.springframework.mock.web.MockMultipartFile(
                "file", "note.m4a", "audio/mp4", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertThatThrownBy(() -> service.posterFichier(
                DEMANDE, fichier, "audio", null, AUTEUR, BRANCHE))
                .isInstanceOf(BusinessException.class);

        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("un vrai MP4 passe, même annoncé « application/octet-stream »")
    void octetStreamPasse() throws Exception {
        // C'est le cas réel : `http.MultipartFile` de Dart déclare ce type par
        // défaut, quelle que soit l'extension du fichier. Refuser là-dessus
        // rejetait toutes les notes vocales.
        var note = new org.springframework.mock.web.MockMultipartFile(
                "file", "note.m4a", "application/octet-stream", enteteMp4());
        when(fichiers.store(any(), any())).thenReturn("discussions/abc.m4a");
        when(fichiers.getUrl(any())).thenReturn("/api/v1/files/discussions/abc.m4a");

        var dto = service.posterFichier(DEMANDE, note, "audio", null, AUTEUR, BRANCHE);

        assertThat(dto.type()).isEqualTo("audio");
    }

    @Test
    @DisplayName("un fichier trop lourd est refusé avant d'être écrit")
    void fichierTropLourd() {
        // Refusé avant l'écriture : accepter puis effacer laisserait le disque
        // se remplir le temps du transfert, et c'est justement le cas qu'on
        // veut borner.
        var gros = new org.springframework.mock.web.MockMultipartFile(
                "file", "long.m4a", "audio/mp4", new byte[11 * 1024 * 1024]);

        assertThatThrownBy(() -> service.posterFichier(
                DEMANDE, gros, "audio", null, AUTEUR, BRANCHE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("10 Mo");

        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("une note vocale acceptée garde le nom du fichier, pas son URL")
    void laNoteVocalePasse() throws Exception {
        var note = new org.springframework.mock.web.MockMultipartFile(
                "file", "note.m4a", "audio/mp4", enteteMp4());
        when(fichiers.store(any(), any())).thenReturn("discussions/abc.m4a");
        when(fichiers.getUrl("discussions/abc.m4a"))
                .thenReturn("/api/v1/files/discussions/abc.m4a");

        var dto = service.posterFichier(DEMANDE, note, "audio", null, AUTEUR, BRANCHE);

        // En base le nom ; à l'écran l'URL. Stocker l'URL la figerait — un
        // changement de préfixe casserait tous les messages anciens.
        ArgumentCaptor<DiscussionMessage> capte =
                ArgumentCaptor.forClass(DiscussionMessage.class);
        verify(messages).save(capte.capture());
        assertThat(capte.getValue().getContent()).isEqualTo("discussions/abc.m4a");
        assertThat(dto.contenu()).isEqualTo("/api/v1/files/discussions/abc.m4a");
    }

    // ── Les notifications hors-app ──────────────────────────────────────

    /** Inscrit au fil l'auteur et un second participant. */
    private void deuxParticipants() {
        for (UUID qui : List.of(AUTEUR, AUTRUI)) {
            var p = new DiscussionParticipant(fil, qui,
                    qui.equals(AUTEUR) ? "docteur" : "laborantin");
            fil.getParticipants().add(p);
            // Déjà inscrits : sans cela le service les réinscrirait, et le fil
            // compterait deux fois la même personne.
            when(participants.findByDiscussionIdAndUserId(any(), org.mockito.ArgumentMatchers.eq(qui)))
                    .thenReturn(Optional.of(p));
        }
    }

    @Test
    @DisplayName("celui qui écrit n'est pas prévenu de son propre message")
    void pasDeNotificationAsoiMeme() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of("jeton-b"));

        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Lame reçue.", null),
                AUTEUR, BRANCHE);

        // Le seul destinataire demandé doit être l'autre : se notifier soi-même
        // ferait vibrer le téléphone de qui vient de reposer le sien.
        ArgumentCaptor<java.util.Collection<UUID>> qui =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(appareils).jetonsDe(qui.capture());
        assertThat(qui.getValue()).containsExactly(AUTRUI);
    }

    @Test
    @DisplayName("la notification porte le début du message")
    void lApercuEstMontre() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of("jeton-b"));

        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Carcinome infiltrant.", null),
                AUTEUR, BRANCHE);

        // Arbitrage du laboratoire : l'aperçu vaut mieux que le déverrouillage
        // à chaque message. La contrepartie — la coupure — est éprouvée plus
        // bas, dans « leLongMessageEstCoupe ».
        ArgumentCaptor<String> titre = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
        verify(notifications).prevenir(any(), titre.capture(), corps.capture(), any());
        assertThat(titre.getValue()).contains("26-0155");
        assertThat(corps.getValue()).contains("AGBO").contains("Carcinome infiltrant.");
    }

    @Test
    @DisplayName("être nommé se dit, parce que cela change l'urgence")
    void leTagSeDit() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of("jeton-b"));

        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "@Marc peux-tu voir ?", AUTRUI),
                AUTEUR, BRANCHE);

        ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
        verify(notifications).prevenir(any(), any(), corps.capture(), any());
        assertThat(corps.getValue()).contains("nommé").contains("peux-tu voir ?");
    }

    @Test
    @DisplayName("sans appareil enrôlé, on n'appelle pas le service d'envoi")
    void aucunAppareil() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of());

        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Lame reçue.", null),
                AUTEUR, BRANCHE);

        verify(notifications, never()).prevenir(any(), any(), any(), any());
    }

    @Test
    @DisplayName("une notification qui échoue n'empêche pas le message d'être posté")
    void lEchecNEmporteRienDAutre() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenThrow(new RuntimeException("réseau coupé"));

        // La conversation est la donnée ; l'alerte n'en est que l'écho. Perdre
        // l'écho est un désagrément, perdre le message serait une faute.
        var dto = service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Lame reçue.", null),
                AUTEUR, BRANCHE);

        assertThat(dto.contenu()).isEqualTo("Lame reçue.");
        verify(messages).save(any());
    }

    @Test
    @DisplayName("un long message est coupé, pas déroulé en entier")
    void leLongMessageEstCoupe() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of("jeton-b"));

        // Le cas qui a motivé la borne : un compte rendu collé dans le fil. Sans
        // coupure, il se déroulerait en entier sur un écran éteint.
        String pave = "Carcinome canalaire infiltrant de grade II. ".repeat(20);
        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", pave, null),
                AUTEUR, BRANCHE);

        ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
        verify(notifications).prevenir(any(), any(), corps.capture(), any());
        assertThat(corps.getValue()).hasSizeLessThan(200).endsWith("…");
    }

    @Test
    @DisplayName("une photo s'annonce par sa nature, faute de texte")
    void laPhotoNaPasDApercu() {
        deuxParticipants();
        when(notifications.estActif()).thenReturn(true);
        when(appareils.jetonsDe(any())).thenReturn(List.of("jeton-b"));

        // Le contenu d'un message photo est une URL de fichier : l'afficher
        // donnerait une ligne d'adresse illisible au lieu d'une information.
        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("photo", "/api/v1/files/x.jpg", null),
                AUTEUR, BRANCHE);

        ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
        verify(notifications).prevenir(any(), any(), corps.capture(), any());
        assertThat(corps.getValue()).contains("photo").doesNotContain("/api/v1/files");
    }

    // ── Qui a le droit d'ouvrir un fil ──────────────────────────────────

    @Test
    @DisplayName("le secrétariat n'accède pas à la discussion d'un dossier")
    void leComptoirEstEcarte() {
        User secretaire = new User();
        secretaire.setLastname("HOUNSA");
        secretaire.setRoles(java.util.List.of(role("secretariat")));
        poserId(secretaire, AUTRUI);
        when(userRepository.findById(AUTRUI)).thenReturn(Optional.of(secretaire));

        // Une discussion de dossier porte des échanges cliniques — l'aspect
        // d'une lame, un doute sur un prélèvement. Le comptoir remet un
        // résultat ; rien dans son travail ne l'appelle à les lire.
        assertThatThrownBy(() -> service.fil(DEMANDE, AUTRUI, BRANCHE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "bonjour", null),
                AUTRUI, BRANCHE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("le laborantin y accède, comme le médecin")
    void leLaboratoireEstAdmis() {
        User technicien = new User();
        technicien.setLastname("KAKPO");
        technicien.setRoles(java.util.List.of(role("laborantin")));
        poserId(technicien, AUTRUI);
        when(userRepository.findById(AUTRUI)).thenReturn(Optional.of(technicien));

        // C'est lui qui prépare la lame et qui a la question à poser.
        var dto = service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Lame recoupée.", null),
                AUTRUI, BRANCHE);
        assertThat(dto.contenu()).isEqualTo("Lame recoupée.");
    }

    @Test
    @DisplayName("un utilisateur sans rôle est refusé, pas laissé passer")
    void sansRoleOnRefuse() {
        User orphelin = new User();
        poserId(orphelin, AUTRUI);
        when(userRepository.findById(AUTRUI)).thenReturn(Optional.of(orphelin));

        // Un rôle absent est une donnée manquante, pas une autorisation. Le
        // sens inverse ouvrirait le fil à tout compte mal configuré.
        assertThatThrownBy(() -> service.fil(DEMANDE, AUTRUI, BRANCHE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("lire un fil n'y inscrit pas — écrire, si")
    void lireNInscritPas() {
        // L'inscription décide de qui reçoit une notification. Elle se faisait
        // à l'ouverture, ce qui était sans conséquence tant que deux personnes
        // seulement pouvaient ouvrir un fil ; depuis qu'il se montre à tout le
        // soin, un coup d'œil abonnait pour des mois.
        service.fil(DEMANDE, AUTEUR, BRANCHE);
        long apresLecture = fil.getParticipants().stream()
                .filter(p -> p.getUserId().equals(AUTEUR)).count();

        service.poster(DEMANDE,
                new DiscussionDtos.NouveauMessage("texte", "Je regarde la lame.", null),
                AUTEUR, BRANCHE);
        long apresEcriture = fil.getParticipants().stream()
                .filter(p -> p.getUserId().equals(AUTEUR)).count();

        assertThat(apresLecture).isZero();
        assertThat(apresEcriture).isOne();
    }
}

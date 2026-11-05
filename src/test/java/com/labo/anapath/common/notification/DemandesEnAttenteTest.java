package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.InvalidOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemandesEnAttenteTest {

    @Mock private DemandeFluidChatRepository repository;
    @Mock private TransactionTemplate transactions;

    private DemandesEnAttente demandes;
    private static final DemandeFluidChat.Type TYPE = DemandeFluidChat.Type.APPEL_RESULTAT;

    @BeforeEach
    void init() {
        demandes = new DemandesEnAttente(repository, transactions);
        // La transaction exécute simplement l'envoi.
        doAnswer(i -> {
            i.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private DemandeFluidChat demande(LocalDateTime creee) {
        DemandeFluidChat d = new DemandeFluidChat();
        d.setId(UUID.randomUUID());
        d.setType(TYPE);
        d.setCibleId(UUID.randomUUID());
        d.setCle("rappel-x");
        d.setCreatedAt(creee);
        when(repository.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private void enAttente(DemandeFluidChat... liste) {
        when(repository.findByTypeAndAbandonneeLeIsNullOrderByUpdatedAtAsc(eq(TYPE), any())).thenReturn(List.of(liste));
    }

    @Test
    @DisplayName("cle : reprend celle de la demande en attente, sinon une neuve")
    void cle() {
        UUID cible = UUID.randomUUID();
        DemandeFluidChat d = demande(LocalDateTime.now());
        when(repository.findByTypeAndCibleIdAndAbandonneeLeIsNull(TYPE, cible)).thenReturn(Optional.of(d));

        assertThat(demandes.cle(TYPE, cible, () -> "neuve")).isEqualTo("rappel-x");
        assertThat(demandes.cle(TYPE, UUID.randomUUID(), () -> "neuve")).isEqualTo("neuve");
        assertThat(DemandesEnAttente.nouvelleCle("rappel")).hasSizeLessThanOrEqualTo(64).startsWith("rappel-");
    }

    @Test
    @DisplayName("enregistrer : une seule demande par envoi, la tentative est comptée, la clé d'origine gardée")
    void enregistrer() {
        UUID cible = UUID.randomUUID();
        DemandeFluidChat d = demande(LocalDateTime.now());
        d.setTentatives(1);
        when(repository.findByTypeAndCibleIdAndAbandonneeLeIsNull(TYPE, cible)).thenReturn(Optional.of(d));

        demandes.enregistrer(TYPE, cible, UUID.randomUUID(), "autre", "x".repeat(300));

        assertThat(d.getTentatives()).isEqualTo(2);
        assertThat(d.getCle()).isEqualTo("rappel-x");
        assertThat(d.getDerniereErreur()).hasSize(255);
    }

    @Test
    @DisplayName("rejouer : plus de 3 jours → abandonnée sans envoi")
    void rejouer_troisJours() {
        DemandeFluidChat vieille = demande(LocalDateTime.now().minusDays(3).minusMinutes(1));
        enAttente(vieille);
        List<UUID> envois = new ArrayList<>();

        demandes.rejouer(TYPE, envois::add);

        assertThat(envois).isEmpty();
        assertThat(vieille.getAbandonneeLe()).isNotNull();
    }

    @Test
    @DisplayName("rejouer : refus (4xx, sans objet) → abandonnée ; envoi réussi → rien à faire ici")
    void rejouer_refus() {
        DemandeFluidChat refusee = demande(LocalDateTime.now().minusHours(1));
        DemandeFluidChat partie = demande(LocalDateTime.now().minusMinutes(30));
        enAttente(refusee, partie);
        // Partie : l'envoi l'a supprimée.
        when(repository.findById(partie.getId())).thenReturn(Optional.empty());

        demandes.rejouer(TYPE, id -> {
            if (id.equals(refusee.getCibleId())) {
                throw new InvalidOperationException("Crédit épuisé");
            }
        });

        assertThat(refusee.getAbandonneeLe()).isNotNull();
        assertThat(refusee.getDerniereErreur()).isEqualTo("Crédit épuisé");
        assertThat(partie.getAbandonneeLe()).isNull();
    }

    @Test
    @DisplayName("rejouer : erreur absorbée par l'envoi mais demande toujours là → passage arrêté")
    void rejouer_panneAbsorbee() {
        DemandeFluidChat premiere = demande(LocalDateTime.now().minusHours(1));
        DemandeFluidChat seconde = demande(LocalDateTime.now().minusMinutes(30));
        enAttente(premiere, seconde);
        List<UUID> envois = new ArrayList<>();

        demandes.rejouer(TYPE, envois::add);

        assertThat(envois).containsExactly(premiere.getCibleId());
    }

    @Test
    @DisplayName("rejouer : FluidChat toujours injoignable → la demande reste, le passage s'arrête")
    void rejouer_toujoursEnPanne() {
        DemandeFluidChat premiere = demande(LocalDateTime.now().minusHours(1));
        DemandeFluidChat seconde = demande(LocalDateTime.now().minusMinutes(30));
        enAttente(premiere, seconde);
        List<UUID> envois = new ArrayList<>();

        demandes.rejouer(TYPE, id -> {
            envois.add(id);
            throw new FluidChatIndisponibleException("indisponible", null);
        });

        assertThat(envois).containsExactly(premiere.getCibleId());
        assertThat(premiere.getAbandonneeLe()).isNull();
        verify(repository, never()).save(any());
    }
}

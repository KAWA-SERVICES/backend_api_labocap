package com.labo.anapath.testorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L'index des demandes, rendu par tranches.
 *
 * <h2>Le défaut corrigé</h2>
 *
 * <p>Il tenait en un seul appel. Mesuré sur le jeu de travail, cet appel rend
 * 709 Ko pour 5 906 demandes, et l'application mobile borne une requête à vingt
 * secondes : il faut donc 284 kbit/s tenus de bout en bout. En EDGE ou en 3G
 * faible — l'ordinaire d'une tournée — le délai expire et rien n'est gardé.
 * Le téléphone jetait six cents kilo-octets déjà descendus, et le nouvel essai
 * repartait de zéro : l'index n'arrivait jamais là où il sert le plus.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("l'index des demandes se rend par tranches")
class IndexParTranchesTest {

    @Mock
    private TestOrderRepository testOrderRepository;

    @InjectMocks
    private TestOrderServiceImpl service;

    private static final UUID BRANCHE = UUID.randomUUID();

    /// Un dépôt qui se comporte comme le vrai.
    ///
    /// <p>`PageImpl` recalcule le total quand le contenu ne cadre pas avec le
    /// rang demandé : un stub qui rend deux entrées au rang 2 d'une tranche de
    /// 500 décrit un état impossible, et c'est le stub qu'on éprouverait alors,
    /// non le service. La tranche est donc découpée pour de bon.</p>
    private void jeuDe(long total) {
        when(testOrderRepository.indexPourLeMobile(
                any(), any(), any(), any(Pageable.class)))
                .thenAnswer(appel -> {
                    Pageable p = appel.getArgument(3);
                    long debut = Math.min(p.getOffset(), total);
                    long fin = Math.min(debut + p.getPageSize(), total);
                    List<EntreeDIndexDto> tranche = new java.util.ArrayList<>();
                    for (long i = debut; i < fin; i++) {
                        tranche.add(entree("26-%04d".formatted(i)));
                    }
                    return new PageImpl<>(tranche, p, total);
                });
    }

    private static EntreeDIndexDto entree(String code) {
        return new EntreeDIndexDto(UUID.randomUUID(), code, "Bernard Payne",
                TestOrderStatus.VALIDATED);
    }

    @Test
    @DisplayName("la tranche demandée est celle qu'on reçoit")
    void trancheDemandee() {
        jeuDe(1200);

        PageDIndexDto tranche = service.indexPourLeMobile(BRANCHE, 36, 2, 500, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(testOrderRepository)
                .indexPourLeMobile(eq(BRANCHE), any(), any(), pageable.capture());

        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(2, 500));
        assertThat(tranche.page()).isEqualTo(2);
        assertThat(tranche.taille()).isEqualTo(500);
        assertThat(tranche.total()).isEqualTo(1200);
        assertThat(tranche.contenu()).hasSize(200);
        assertThat(tranche.derniere()).isTrue();
    }

    @Test
    @DisplayName("la taille de tranche est plafonnée par le serveur, pas par le client")
    void taillePlafonnee() {
        // « size=100000 » rétablirait exactement le défaut qu'on corrige. Le
        // téléphone n'a aucun moyen de connaître le poids d'une entrée ; le
        // serveur, si.
        jeuDe(0);
        assertThat(service.indexPourLeMobile(BRANCHE, 36, 0, 100_000, null).taille())
                .isEqualTo(2000);

        // Et un plancher : des tranches de une multiplieraient les allers-retours,
        // dont le coût fixe dépasse alors ce qu'elles transportent.
        assertThat(service.indexPourLeMobile(BRANCHE, 36, 0, 1, null).taille())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("un rang négatif ne fait pas échouer l'appel")
    void rangNegatif() {
        jeuDe(0);
        assertThat(service.indexPourLeMobile(BRANCHE, 36, -3, 500, null).page())
                .isZero();
    }

    @Test
    @DisplayName("le premier appel fige le jeu, et rend l'instant qui le fige")
    void premierAppelFigeLeJeu() {
        jeuDe(0);
        LocalDateTime avant = LocalDateTime.now();

        PageDIndexDto tranche = service.indexPourLeMobile(BRANCHE, 36, 0, 500, null);

        assertThat(tranche.jusqua()).isNotNull();
        assertThat(tranche.jusqua()).isBetween(avant.minusSeconds(1),
                LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("l'instant repassé par le client est celui qui borne les tranches suivantes")
    void instantRepasse() {
        // Sans cela, une demande enregistrée au comptoir pendant le
        // rapatriement s'insère en tête du tri par date décroissante, décale
        // tout d'un rang, et fait manquer définitivement une entrée — celle-là
        // même qu'on viendra scanner.
        jeuDe(0);
        LocalDateTime fige = LocalDateTime.of(2026, 9, 30, 8, 0);

        service.indexPourLeMobile(BRANCHE, 12, 3, 500, fige);

        ArgumentCaptor<LocalDateTime> depuis = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> jusqua = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(testOrderRepository).indexPourLeMobile(
                eq(BRANCHE), depuis.capture(), jusqua.capture(), any(Pageable.class));

        assertThat(jusqua.getValue()).isEqualTo(fige);
        // La profondeur se compte depuis l'instant figé, et non depuis
        // maintenant : sinon la fenêtre glisserait d'une tranche à l'autre, et
        // les entrées les plus anciennes sortiraient du jeu en cours de route.
        assertThat(depuis.getValue()).isEqualTo(fige.minusMonths(12));
    }

    @Test
    @DisplayName("la profondeur reste bornée à trente-six mois")
    void profondeurBornee() {
        jeuDe(0);
        LocalDateTime fige = LocalDateTime.of(2026, 9, 30, 8, 0);

        service.indexPourLeMobile(BRANCHE, 999, 0, 500, fige);

        ArgumentCaptor<LocalDateTime> depuis = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(testOrderRepository).indexPourLeMobile(
                eq(BRANCHE), depuis.capture(), any(), any(Pageable.class));
        assertThat(depuis.getValue()).isEqualTo(fige.minusMonths(36));
    }

    @Test
    @DisplayName("la dernière tranche se signale, pour que le client sache s'arrêter")
    void derniereTranche() {
        // Le client ne peut pas le déduire : une tranche pleine peut être la
        // dernière, et compter les entrées reçues contre le total supposerait
        // qu'aucune n'a échoué en route.
        // 120 entrées par tranches de 50 : trois tranches, dont la dernière
        // est incomplète. C'est la forme qu'a réellement un index au bout.
        jeuDe(120);

        assertThat(service.indexPourLeMobile(BRANCHE, 36, 0, 50, null).derniere()).isFalse();
        assertThat(service.indexPourLeMobile(BRANCHE, 36, 1, 50, null).derniere()).isFalse();

        PageDIndexDto fin = service.indexPourLeMobile(BRANCHE, 36, 2, 50, null);
        assertThat(fin.derniere()).isTrue();
        assertThat(fin.contenu()).hasSize(20);
    }

    @Test
    @DisplayName("un jeu vide rend une tranche vide, et non une erreur")
    void jeuVide() {
        jeuDe(0);

        PageDIndexDto tranche = service.indexPourLeMobile(BRANCHE, 36, 0, 500, null);

        assertThat(tranche.contenu()).isEmpty();
        assertThat(tranche.total()).isZero();
        assertThat(tranche.derniere()).isTrue();
    }
}

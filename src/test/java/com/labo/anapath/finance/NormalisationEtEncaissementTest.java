package com.labo.anapath.finance;

import com.labo.anapath.common.exception.InvalidOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Déclarer suppose d'avoir encaissé.
 *
 * <h2>Pourquoi cet ordre</h2>
 *
 * <p>La charge utile envoyée à la DGI porte un bloc « payment » que le serveur
 * lit sur la facture. Tant qu'elle n'est pas réglée, ce bloc est absent : la
 * déclaration part en annonçant une facture non réglée. Déclarer d'abord
 * enverrait donc une déclaration fausse, qu'aucun encaissement ultérieur ne
 * corrigerait — un document fiscal ne se reprend pas.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NormalisationEtEncaissementTest {

    private static final UUID FACTURE = UUID.randomUUID();
    private static final UUID BRANCHE = UUID.randomUUID();

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private FluidInvoiceClient client;
    @Mock private FinanceMapper financeMapper;
    @Mock private InvoiceService invoiceService;
    /** La normalisation publie InvoiceValidatedEvent : le SMS de facture s'y branche. */
    @Mock private ApplicationEventPublisher eventPublisher;

    private FluidInvoiceServiceImpl service;
    private Invoice facture;

    @BeforeEach
    void poser() {
        service = new FluidInvoiceServiceImpl(
                invoiceRepository, client, new FluidInvoiceProperties(),
                financeMapper, eventPublisher, invoiceService, new TamponDuDossier());

        facture = new Invoice();
        facture.setId(FACTURE);
        facture.setBranchId(BRANCHE);
        facture.setStatusInvoice(0);
        facture.setPaid(false);
        facture.setTotal(java.math.BigDecimal.valueOf(15000));
        // Une facture sans ligne est refusée avant tout appel : sans ce détail,
        // les essais échoueraient pour une raison sans rapport avec la règle.
        InvoiceDetail ligne = new InvoiceDetail();
        ligne.setTestName("Examen histologique");
        ligne.setTotal(java.math.BigDecimal.valueOf(15000));
        ligne.setQuantity(1);
        facture.getDetails().add(ligne);
        when(invoiceRepository.findByIdAndBranchId(eq(FACTURE), any())).thenReturn(Optional.of(facture));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("sans mode de paiement, une facture impayée n'est pas déclarée")
    void leModeEstExige() {
        assertThatThrownBy(() -> service.normaliser(FACTURE, BRANCHE, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("mode de paiement");

        // Rien ne doit partir : une déclaration ne se retire pas.
        verify(client, never()).emettre(any(), any());
    }

    @Test
    @DisplayName("un mode fait d'espaces ne vaut pas un mode")
    void leBlancNeComptePas() {
        assertThatThrownBy(() -> service.normaliser(FACTURE, BRANCHE, "   "))
                .isInstanceOf(InvalidOperationException.class);
        verify(client, never()).emettre(any(), any());
    }

    @Test
    @DisplayName("une facture déjà réglée se déclare sans réencaisser")
    void pasDeSecondEncaissement() {
        facture.setPaid(true);
        facture.setPayment("ESPECES");
        when(client.emettre(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, "MOBILEMONEY");

        // Encaisser deux fois créditerait la caisse du double du montant.
        verify(invoiceService, never()).markAsPaid(any(), any(), any());
    }

    @Test
    @DisplayName("un avoir se déclare sans encaissement : il contrepasse")
    void lAvoirNEncaissePas() {
        facture.setStatusInvoice(1);
        // Un avoir se rattache à la vente qu'il contrepasse : sans elle, la
        // charge utile n'a pas de référence à donner à la DGI.
        Invoice vente = new Invoice();
        vente.setId(UUID.randomUUID());
        vente.setCode("FV26-0001");
        // Et cette vente doit elle-même être déclarée : un avoir répond à une
        // déclaration, il ne peut pas la précéder.
        vente.setFluidinvoiceId("b3c1-de-la-vente");
        facture.setReference(vente);
        when(client.emettreAvoir(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, null);

        verify(invoiceService, never()).markAsPaid(any(), any(), any());
    }

    @Test
    @DisplayName("l'encaissement précède la déclaration, avec le mode choisi")
    void encaisserPuisDeclarer() {
        when(client.emettre(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, " MOBILEMONEY ");

        ArgumentCaptor<InvoiceStatusUpdateDto> regle =
                ArgumentCaptor.forClass(InvoiceStatusUpdateDto.class);
        verify(invoiceService).markAsPaid(eq(FACTURE), regle.capture(), eq(BRANCHE));
        // Les espaces autour du mode viennent des listes déroulantes ; les
        // laisser produirait un libellé que la DGI ne reconnaît pas.
        assertThat(regle.getValue().getPayment()).isEqualTo("MOBILEMONEY");
    }

    @Test
    @DisplayName("le destinataire choisi à la déclaration remplace celui de la facture")
    void leDestinataireChoisi() {
        when(client.emettre(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, "ESPECES", new IdentiteDeFacturation(
                "  CLINIQUE LA PROVIDENCE  ", "  Cotonou, Ganhi  ", " 3201900123456 "));

        // Rogné : ces valeurs viennent d'un champ de saisie, et une espace de
        // tête partirait telle quelle à la DGI.
        assertThat(facture.getClientName()).isEqualTo("CLINIQUE LA PROVIDENCE");
        assertThat(facture.getClientAddress()).isEqualTo("Cotonou, Ganhi");
        assertThat(facture.getClientIfu()).isEqualTo("3201900123456");
        // Figée, sinon la revalidation du bon rendrait la facture au patient.
        assertThat(facture.isFacturationFigee()).isTrue();
    }

    @Test
    @DisplayName("sans destinataire, la facture garde le sien")
    void sansDestinataire() {
        facture.setClientName("DOTOU Justine");
        when(client.emettre(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, "ESPECES", null);

        assertThat(facture.getClientName()).isEqualTo("DOTOU Justine");
        assertThat(facture.isFacturationFigee()).isFalse();
    }

    @Test
    @DisplayName("un destinataire sans nom est ignoré")
    void destinataireSansNom() {
        // Un formulaire ouvert puis refermé sans rien saisir ne doit pas
        // effacer le destinataire de la facture.
        facture.setClientName("DOTOU Justine");
        when(client.emettre(any(), any())).thenReturn(new FluidInvoiceResponseDto());

        service.normaliser(FACTURE, BRANCHE, "ESPECES",
                new IdentiteDeFacturation("   ", "Cotonou", "320190"));

        assertThat(facture.getClientName()).isEqualTo("DOTOU Justine");
        assertThat(facture.isFacturationFigee()).isFalse();
    }
}

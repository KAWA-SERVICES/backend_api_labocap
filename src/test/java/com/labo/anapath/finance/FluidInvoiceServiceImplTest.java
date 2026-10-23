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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Référence transmise à l'éditeur pour un avoir.
 *
 * <p>Un avoir doit désigner la vente qu'il annule. Les factures reprises du
 * Laravel d'origine portent des colonnes <b>vides</b> plutôt que {@code NULL},
 * et le code de normalisation y vit tantôt dans {@code code_mecef}, tantôt dans
 * {@code code_normalise} selon que la machine e-MECeF a répondu ou que le
 * caissier l'a saisi. Ces tests fixent ce que le service en fait.</p>
 */
@ExtendWith(MockitoExtension.class)
class FluidInvoiceServiceImplTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private FluidInvoiceClient client;
    @Mock private FinanceMapper financeMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private InvoiceService invoiceService;
    @Mock private TamponDuDossier tampon;

    private FluidInvoiceProperties properties;
    private FluidInvoiceServiceImpl service;

    private static final UUID AVOIR_ID = UUID.randomUUID();
    private static final UUID BRANCHE = UUID.randomUUID();

    @BeforeEach
    void setup() {
        properties = new FluidInvoiceProperties();
        properties.setEnabled(true);
        properties.setApiKey("fni_test_xxx");
        service = new FluidInvoiceServiceImpl(
                invoiceRepository, client, properties, financeMapper,
                eventPublisher, invoiceService, tampon);
    }

    /** Un avoir portant une ligne, rattaché à l'originale fournie. */
    private Invoice avoirRattacheA(Invoice originale) {
        InvoiceDetail ligne = new InvoiceDetail();
        ligne.setTestName("Examen");
        ligne.setTotal(BigDecimal.valueOf(10_000));
        ligne.setQuantity(1);

        Invoice avoir = new Invoice();
        avoir.setId(AVOIR_ID);
        avoir.setCode("FA260015");
        avoir.setBranchId(BRANCHE);
        avoir.setStatusInvoice(1); // 1 = avoir
        avoir.setReference(originale);
        avoir.setDetails(List.of(ligne));
        return avoir;
    }

    private Invoice originale(String codeMecef, String codeNormalise, String fluidinvoiceId) {
        Invoice o = new Invoice();
        o.setId(UUID.randomUUID());
        o.setCode("FA252000");
        o.setCodeMecef(codeMecef);
        o.setCodeNormalise(codeNormalise);
        o.setFluidinvoiceId(fluidinvoiceId);
        return o;
    }

    private void editeurRepondOk() {
        FluidInvoiceResponseDto reponse = new FluidInvoiceResponseDto();
        reponse.setId("fni-123");
        lenient().when(client.emettreAvoir(any(), anyString())).thenReturn(reponse);
        lenient().when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private FluidInvoiceRequestDto payloadEnvoye() {
        ArgumentCaptor<FluidInvoiceRequestDto> capteur =
                ArgumentCaptor.forClass(FluidInvoiceRequestDto.class);
        verify(client).emettreAvoir(capteur.capture(), anyString());
        return capteur.getValue();
    }

    @Test
    @DisplayName("code_mecef vide → le code saisi à la main sert de référence")
    void codeMecefVide_replieSurCodeNormalise() {
        // Le cas qui produisait un MISSING_REFERENCE : la garde ne testait que
        // null, et une chaîne vide partait telle quelle chez l'éditeur.
        Invoice avoir = avoirRattacheA(originale("", "345678yghjkhRTY678907654", null));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));
        editeurRepondOk();

        service.normaliser(AVOIR_ID, BRANCHE, null);

        assertThat(payloadEnvoye().reference()).isEqualTo("345678yghjkhRTY678907654");
    }

    @Test
    @DisplayName("code_mecef renseigné → il prime sur le code saisi à la main")
    void codeMecefRenseigne_prime() {
        Invoice avoir = avoirRattacheA(originale("TEST-PXP6-CJB6", "saisi-a-la-main", null));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));
        editeurRepondOk();

        service.normaliser(AVOIR_ID, BRANCHE, null);

        assertThat(payloadEnvoye().reference()).isEqualTo("TEST-PXP6-CJB6");
    }

    @Test
    @DisplayName("aucun code, mais l'originale est connue de l'éditeur → son identifiant")
    void sansCode_utiliseLIdentifiantFluidInvoice() {
        Invoice avoir = avoirRattacheA(originale("", "", "1f41c97a-23a2-4977"));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));
        editeurRepondOk();

        service.normaliser(AVOIR_ID, BRANCHE, null);

        assertThat(payloadEnvoye().originalInvoiceId()).isEqualTo("1f41c97a-23a2-4977");
        assertThat(payloadEnvoye().reference()).isNull();
    }

    @Test
    @DisplayName("originale sans aucune référence → refus lisible, aucun appel à l'éditeur")
    void sansAucuneReference_refusLisible() {
        // Vaut mieux ce message que le MISSING_REFERENCE de l'éditeur, que le
        // caissier ne peut pas relier à la facture de vente.
        Invoice avoir = avoirRattacheA(originale("", "", ""));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));

        assertThatThrownBy(() -> service.normaliser(AVOIR_ID, BRANCHE, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("doit être normalisée avant son avoir");
        verify(client, never()).emettreAvoir(any(), anyString());
    }

    @Test
    @DisplayName("ligne sans test_name → le libellé du catalogue prend le relais")
    void ligneSansNom_repliSurLeCatalogue() {
        // Des lignes reprises de Laravel n'ont pas de test_name. Sans repli,
        // l'éditeur refusait la déclaration (Items[0].Name required).
        com.labo.anapath.test.LabTest examen = new com.labo.anapath.test.LabTest();
        examen.setName("Appendicectomie");

        InvoiceDetail ligne = new InvoiceDetail();
        ligne.setTestName(null);
        ligne.setLabTest(examen);
        ligne.setTotal(BigDecimal.valueOf(35_000));
        ligne.setQuantity(1);

        Invoice avoir = avoirRattacheA(originale("MECEF-123", null, null));
        avoir.setDetails(List.of(ligne));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));
        editeurRepondOk();

        service.normaliser(AVOIR_ID, BRANCHE, null);

        assertThat(payloadEnvoye().items().get(0).name()).isEqualTo("Appendicectomie");
    }

    @Test
    @DisplayName("ligne sans nom ni catalogue → refus lisible, l'éditeur n'est pas appelé")
    void ligneSansAucunNom_refusLisible() {
        InvoiceDetail ligne = new InvoiceDetail();
        ligne.setTestName("   ");
        ligne.setTotal(BigDecimal.valueOf(35_000));
        ligne.setQuantity(1);

        Invoice avoir = avoirRattacheA(originale("MECEF-123", null, null));
        avoir.setDetails(List.of(ligne));
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));

        assertThatThrownBy(() -> service.normaliser(AVOIR_ID, BRANCHE, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("libellé");
        verify(client, never()).emettreAvoir(any(), anyString());
    }

    @Test
    @DisplayName("avoir non rattaché à une vente → refus explicite")
    void avoirSansOriginale_refus() {
        Invoice avoir = avoirRattacheA(null);
        when(invoiceRepository.findByIdAndBranchId(eq(AVOIR_ID), any())).thenReturn(Optional.of(avoir));

        assertThatThrownBy(() -> service.normaliser(AVOIR_ID, BRANCHE, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("rattaché à aucune facture de vente");
        verify(client, never()).emettreAvoir(any(), anyString());
    }

    // -------------------------------------------------------------------------
    // QR de la demande d'examen sur le document normalisé
    // -------------------------------------------------------------------------

    private static final UUID VENTE_ID = UUID.randomUUID();
    private static final byte[] DOCUMENT = {1, 2, 3};

    /** Facture de vente normalisée, à un particulier, avec demande EX26-0001. */
    private Invoice venteNormalisee() {
        com.labo.anapath.testorder.TestOrder demande = new com.labo.anapath.testorder.TestOrder();
        demande.setCode("EX26-0001");
        Invoice vente = new Invoice();
        vente.setId(VENTE_ID);
        vente.setBranchId(BRANCHE);
        vente.setFluidinvoiceId("fi-1");
        vente.setTestOrder(demande);
        when(invoiceRepository.findByIdAndBranchId(VENTE_ID, BRANCHE)).thenReturn(Optional.of(vente));
        when(client.telechargerDocument("fi-1")).thenReturn(DOCUMENT);
        return vente;
    }

    @Test
    @DisplayName("document normalisé d'un particulier → QR de la demande posé")
    void documentDUnParticulier_tamponne() {
        venteNormalisee();
        byte[] tamponne = {9};
        when(tampon.apposer(DOCUMENT, "EX26-0001")).thenReturn(tamponne);

        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(tamponne);
    }

    @Test
    @DisplayName("acheteur avec IFU, avoir, facture sans demande ou demande supprimée → document rendu tel quel")
    void horsParticulierAvecDemande_documentIntact() {
        venteNormalisee().setClientIfu("3201810410828");
        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(DOCUMENT);

        venteNormalisee().setStatusInvoice(1);
        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(DOCUMENT);

        venteNormalisee().setTestOrder(null);
        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(DOCUMENT);

        venteNormalisee().getTestOrder().setCode("  ");
        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(DOCUMENT);

        // Demande supprimée depuis : le proxy paresseux ne se résout plus.
        com.labo.anapath.testorder.TestOrder supprimee =
                org.mockito.Mockito.mock(com.labo.anapath.testorder.TestOrder.class);
        when(supprimee.getCode()).thenThrow(new jakarta.persistence.EntityNotFoundException());
        venteNormalisee().setTestOrder(supprimee);
        assertThat(service.telechargerDocument(VENTE_ID, BRANCHE)).isSameAs(DOCUMENT);

        verify(tampon, never()).apposer(any(), anyString());
    }
}

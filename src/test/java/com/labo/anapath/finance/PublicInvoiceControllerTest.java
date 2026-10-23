package com.labo.anapath.finance;

import com.labo.anapath.common.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le lien du SMS sert la facture normalisée quand elle existe, la facture
 * LabSys sinon — et jamais une page d'erreur parce que FluidInvoice se tait.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Lien public de la facture")
class PublicInvoiceControllerTest {

    private static final String JETON = "jeton-abc";
    private static final byte[] NORMALISEE = {1};
    private static final byte[] LABSYS = {2};

    @Mock private InvoiceShareLinkService shareLinkService;
    @Mock private InvoicePdfService invoicePdfService;
    @Mock private FluidInvoiceService fluidInvoiceService;
    @Mock private com.labo.anapath.common.supervision.CompteurDAlertes compteur;

    private PublicInvoiceController controleur;
    private Invoice facture;

    @BeforeEach
    void poser() {
        controleur = new PublicInvoiceController(shareLinkService, invoicePdfService, fluidInvoiceService, compteur);
        facture = new Invoice();
        facture.setId(UUID.randomUUID());
        facture.setBranchId(UUID.randomUUID());
        facture.setCode("FA260007");
        org.mockito.Mockito.lenient().when(shareLinkService.resoudre(JETON)).thenReturn(Optional.of(facture));
    }

    @Test
    @DisplayName("facture normalisée → le PDF FluidInvoice tamponné")
    void factureNormalisee_documentNormalise() {
        facture.setFluidinvoiceId("fi-1");
        when(fluidInvoiceService.telechargerDocument(facture.getId(), facture.getBranchId())).thenReturn(NORMALISEE);

        assertThat(controleur.telecharger(JETON).getBody()).isSameAs(NORMALISEE);
        verify(invoicePdfService, never()).generatePdf(any(), any());
    }

    @Test
    @DisplayName("facture pas encore normalisée → la facture LabSys")
    void factureNonNormalisee_factureLabsys() {
        when(invoicePdfService.generatePdf(facture.getId(), facture.getBranchId())).thenReturn(LABSYS);

        assertThat(controleur.telecharger(JETON).getBody()).isSameAs(LABSYS);
        verify(fluidInvoiceService, never()).telechargerDocument(any(), any());
    }

    @Test
    @DisplayName("FluidInvoice injoignable → la facture LabSys plutôt qu'une erreur")
    void fluidInvoiceEnEchec_factureLabsys() {
        facture.setFluidinvoiceId("fi-1");
        when(fluidInvoiceService.telechargerDocument(any(), any()))
                .thenThrow(new ExternalApiException("FluidInvoice injoignable"));
        when(invoicePdfService.generatePdf(facture.getId(), facture.getBranchId())).thenReturn(LABSYS);

        assertThat(controleur.telecharger(JETON).getBody()).isSameAs(LABSYS);
    }

    @Test
    @DisplayName("jeton inconnu ou expiré → page d'explication, et le refus est compté pour la supervision")
    void jetonRefuse_compte() {
        when(shareLinkService.resoudre("inconnu")).thenReturn(Optional.empty());
        when(shareLinkService.resoudre("expire")).thenThrow(new InvoiceShareLinkService.LienExpireException());

        assertThat(controleur.telecharger("inconnu").getStatusCode().value()).isEqualTo(404);
        assertThat(controleur.telecharger("expire").getStatusCode().value()).isEqualTo(410);
        verify(compteur, org.mockito.Mockito.times(2)).lienDeFactureRefuse();
    }
}

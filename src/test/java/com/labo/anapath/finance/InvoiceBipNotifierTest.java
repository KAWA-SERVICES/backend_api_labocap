package com.labo.anapath.finance;

import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.common.notification.DemandeFluidChat;
import com.labo.anapath.common.notification.DemandesEnAttente;
import com.labo.anapath.common.notification.FluidChatClient;
import com.labo.anapath.common.notification.FluidChatIndisponibleException;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.report.ReglagesDeRappel;
import com.labo.anapath.testorder.TestOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// Réglages préparés pour tous les tests, que les cas « rien ne part » ne lisent pas.
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceBipNotifierTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceBipRepository invoiceBipRepository;
    @Mock private FluidChatClient fluidChatClient;
    @Mock private ReglagesDeRappel reglagesDeRappel;
    @Mock private DemandesEnAttente demandes;

    @InjectMocks private InvoiceBipNotifier notifier;

    private final UUID INVOICE_ID = UUID.randomUUID();
    private static final ReglagesDeRappel.Plage PLAGE =
            new ReglagesDeRappel.Plage(LocalTime.of(8, 0), LocalTime.of(18, 0), Set.of(1, 2, 3, 4, 5));

    @BeforeEach
    void reglages() {
        when(reglagesDeRappel.audioBip()).thenReturn("custom/9");
        when(reglagesDeRappel.plage()).thenReturn(PLAGE);
        when(demandes.cle(any(), any(), any()))
                .thenAnswer(i -> ((java.util.function.Supplier<String>) i.getArgument(2)).get());
        when(fluidChatClient.bip(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FluidChatClient.Appel(70, "queued", null, 0, 0));
    }

    /** Facture d'un particulier : le patient est porté par le bon d'examen. */
    private Invoice facture(String telephone1, String telephone2) {
        Patient patient = new Patient();
        patient.setTelephone1(telephone1);
        patient.setTelephone2(telephone2);
        TestOrder bon = new TestOrder();
        bon.setPatient(patient);
        bon.setCode("26-0042");

        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setCode("FA260001");
        invoice.setTestOrder(bon);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private void valider() {
        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));
    }

    @Test
    @DisplayName("facture validée → bip au +229, audio de décroché, plage réglementaire, facture marquée")
    void bipeEtMarque() {
        facture("97000001", null);

        valider();

        verify(fluidChatClient).bip(eq("+2290197000001"), eq("custom/9"),
                eq(PLAGE.versCallWindow()), eq("BIP-FA260001"), eq("26-0042"), any(), any());
        verify(invoiceBipRepository).marquerBip(eq(INVOICE_ID), any());
    }

    @Test
    @DisplayName("téléphone 2 quand le premier manque")
    void telephone2() {
        facture(" ", "66000002");

        valider();

        verify(fluidChatClient).bip(eq("+2290166000002"), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("second événement de la même facture (encaissée puis normalisée) : pas de second bip")
    void dejaBipee() {
        facture("97000001", null).setBipSentAt(LocalDateTime.now());

        valider();

        verify(fluidChatClient, never()).bip(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("facture d'avoir ou patient sans numéro : aucun bip")
    void avoirOuSansNumero() {
        facture("97000001", null).setStatusInvoice(1);
        valider();

        facture(null, null);
        valider();

        verify(fluidChatClient, never()).bip(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("lien audio du bip non renseigné : aucun bip")
    void sansAudio() {
        facture("97000001", null);
        when(reglagesDeRappel.audioBip()).thenReturn(null);

        valider();

        verify(fluidChatClient, never()).bip(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("FluidChat en panne : rien ne remonte, la facture reste à biper")
    void panne() {
        facture("97000001", null);
        when(fluidChatClient.bip(any(), any(), any(), any(), any(), any(), any())).thenThrow(new ExternalApiException("panne"));

        valider();

        verify(invoiceBipRepository, never()).marquerBip(any(), any());
    }

    @Test
    @DisplayName("deux numéros : le téléphone 2 en fallback_to, clé bip-…")
    void numeroSecondaire() {
        facture("97000001", "66000002");

        valider();

        verify(fluidChatClient).bip(eq("+2290197000001"), any(), any(), any(), any(),
                eq("+2290166000002"), org.mockito.ArgumentMatchers.startsWith("bip-"));
    }

    @Test
    @DisplayName("FluidChat injoignable : le bip est gardé pour renvoi avec sa clé, facture non marquée")
    void injoignable_garde() {
        facture("97000001", null);
        org.mockito.Mockito.doReturn("bip-garde").when(demandes).cle(any(), any(), any());
        when(fluidChatClient.bip(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new FluidChatIndisponibleException("Service d'appel indisponible", null));

        valider();

        verify(demandes).enregistrer(eq(DemandeFluidChat.Type.BIP_FACTURE), eq(INVOICE_ID), any(), eq("bip-garde"), any());
        verify(invoiceBipRepository, never()).marquerBip(any(), any());
    }

    @Test
    @DisplayName("bip en attente devenu sans objet (facture déjà bipée) : il quitte la file sans appel")
    void sansObjet_resolu() {
        facture("97000001", null).setBipSentAt(LocalDateTime.now());

        valider();

        verify(demandes).resoudre(DemandeFluidChat.Type.BIP_FACTURE, INVOICE_ID);
        verify(fluidChatClient, never()).bip(any(), any(), any(), any(), any(), any(), any());
    }
}

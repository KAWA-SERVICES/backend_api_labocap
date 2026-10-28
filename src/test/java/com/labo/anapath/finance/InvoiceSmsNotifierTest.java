package com.labo.anapath.finance;

import com.labo.anapath.common.exception.ExternalApiException;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.common.notification.SmsSender;
import com.labo.anapath.common.notification.SmsTemplates;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.testorder.TestOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceSmsNotifierTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceShareLinkService shareLinkService;
    @Mock private SmsTemplates smsTemplates;
    @Mock private SmsSender smsSender;

    @InjectMocks private InvoiceSmsNotifier notifier;

    private final UUID INVOICE_ID = UUID.randomUUID();
    private static final String LIEN = "https://api.caap.bj/api/v1/public/invoices/jeton-abc";

    private Invoice factureAvecPatient(String telephone) {
        Patient patient = new Patient();
        patient.setTelephone1(telephone);

        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setCode("FA260001");
        invoice.setPatient(patient);
        return invoice;
    }

    @Test
    @DisplayName("facture validée → SMS contenant le lien public, au numéro international")
    void envoieLeSmsAvecLeLien() {
        Invoice invoice = factureAvecPatient("97000001");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), eq("FA260001"), any(), eq(LIEN)))
                .thenReturn("Votre facture FA260001 est disponible : " + LIEN);

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
        verify(smsSender).envoyer(eq("2290197000001"), corps.capture(),
                eq(SmsSender.SOURCE_FACTURE), eq(INVOICE_ID));
        assertThat(corps.getValue()).contains(LIEN).contains("FA260001");
    }

    @Test
    @DisplayName("clé d'idempotence = identifiant de la facture, pour n'en facturer qu'un")
    void utiliseLIdentifiantDeLaFactureCommeCleDIdempotence() {
        // Si l'application redémarre entre l'envoi et la pose de share_sms_sent_at,
        // l'événement repart. C'est FluidPay qui écarte alors le doublon, sur cette clé.
        Invoice invoice = factureAvecPatient("97000001");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        verify(smsSender).envoyer(anyString(), anyString(), anyString(), eq(INVOICE_ID));
    }

    @Test
    @DisplayName("patient du bon d'examen utilisé quand la facture n'en porte pas")
    void reprendLePatientDuBonDExamen() {
        Patient patient = new Patient();
        patient.setTelephone1("97000002");
        TestOrder order = new TestOrder();
        order.setPatient(patient);
        order.setCode("EX26-0002");

        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setCode("FA260002");
        invoice.setTestOrder(order);

        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        verify(smsSender).envoyer(eq("2290197000002"), anyString(), anyString(), any());
        // Le code de la demande part dans le texte du SMS.
        verify(smsTemplates).smsFacture(any(), eq("FA260002"), eq("EX26-0002"), eq(LIEN));
    }

    @Test
    @DisplayName("aucun numéro connu → aucun SMS, aucun lien émis")
    void sansTelephone_nEnvoieRien() {
        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setCode("FA260003");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        verify(smsSender, never()).envoyer(any(), any(), any(), any());
        verify(shareLinkService, never()).urlDeTelechargement(any());
    }

    @Test
    @DisplayName("passerelle non configurée → aucune exception ne remonte à la caisse")
    void sansConfiguration_lErreurEstAbsorbee() {
        Invoice invoice = factureAvecPatient("97000004");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");
        doThrow(new ExternalApiException("Aucune passerelle SMS configurée"))
                .when(smsSender).envoyer(any(), any(), any(), any());

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        // L'encaissement est acquis : rien ne doit sortir de l'écouteur.
        assertThat(invoice.getShareSmsSentAt()).isNull();
    }

    @Test
    @DisplayName("panne de la passerelle → l'erreur est absorbée, la validation reste acquise")
    void panneDeLaPasserelle_estAbsorbee() {
        Invoice invoice = factureAvecPatient("97000005");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");
        doThrow(new ExternalApiException("FluidPay injoignable"))
                .when(smsSender).envoyer(any(), any(), any(), any());

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));
        // Aucune exception ne doit sortir : l'écouteur avale et journalise.
    }

    @Test
    @DisplayName("facture déjà notifiée → aucun second SMS")
    void nEnvoiePasDeuxFoisLeMemeSms() {
        // Une facture encaissée puis normalisée déclenche deux fois l'événement.
        // Le client n'a pas à recevoir deux SMS, ni le laboratoire à en payer deux.
        Invoice invoice = factureAvecPatient("97000001");
        invoice.setShareSmsSentAt(LocalDateTime.now().minusMinutes(5));
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));

        verify(smsSender, never()).envoyer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("envoi réussi → date d'envoi enregistrée, envoi échoué → non")
    void marqueLEnvoiSeulementApresReponseDeLaPasserelle() {
        Invoice invoice = factureAvecPatient("97000001");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));
        assertThat(invoice.getShareSmsSentAt()).isNotNull();

        // Un envoi qui échoue ne doit pas laisser la facture pour notifiée : le
        // geste suivant doit pouvoir retenter.
        Invoice echouee = factureAvecPatient("97000002");
        when(invoiceRepository.findWithLockById(INVOICE_ID)).thenReturn(Optional.of(echouee));
        when(shareLinkService.urlDeTelechargement(echouee)).thenReturn(LIEN);
        doThrow(new ExternalApiException("FluidPay injoignable"))
                .when(smsSender).envoyer(eq("2290197000002"), anyString(), anyString(), any());

        notifier.onInvoiceValidated(new InvoiceValidatedEvent(INVOICE_ID));
        assertThat(echouee.getShareSmsSentAt()).isNull();
    }

    // --- Envoi à la demande (bouton « Envoyer le SMS ») ---

    private final UUID BRANCH_ID = UUID.randomUUID();

    private Invoice factureNormalisee(String telephone) {
        Invoice invoice = factureAvecPatient(telephone);
        invoice.setNormalizedUrl("https://invoice.fluidpay.link/v1/invoices/x/pdf");
        when(invoiceRepository.findWithLockByIdAndBranchId(INVOICE_ID, BRANCH_ID)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    @DisplayName("à la demande : SMS jamais parti → envoyé, date d'envoi posée, numéro renvoyé")
    void aLaDemande_envoieEtMarque() {
        Invoice invoice = factureNormalisee("97000001");
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");

        String destinataire = notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID);

        assertThat(destinataire).isEqualTo("2290197000001");
        verify(smsSender).envoyer(eq("2290197000001"), eq("message"),
                eq(SmsSender.SOURCE_FACTURE), eq(INVOICE_ID));
        assertThat(invoice.getShareSmsSentAt()).isNotNull();
    }

    @Test
    @DisplayName("à la demande : SMS déjà parti, facture non normalisée ou avoir → refus, rien n'est envoyé")
    void aLaDemande_refusSansEnvoi() {
        Invoice dejaEnvoyee = factureNormalisee("97000001");
        dejaEnvoyee.setShareSmsSentAt(LocalDateTime.now());
        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("déjà parti");

        Invoice nonNormalisee = factureNormalisee("97000001");
        nonNormalisee.setNormalizedUrl(null);
        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("Normalisez");

        Invoice avoir = factureNormalisee("97000001");
        avoir.setStatusInvoice(1);
        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("avoir");

        verify(smsSender, never()).envoyer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("à la demande : patient sans numéro → refus explicite")
    void aLaDemande_sansNumero() {
        factureNormalisee(null);
        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("Aucun numéro");
    }

    @Test
    @DisplayName("à la demande : passerelle en panne → l'erreur remonte à l'agent, la facture reste non marquée")
    void aLaDemande_panneRemonte() {
        Invoice invoice = factureNormalisee("0197000001");
        when(shareLinkService.urlDeTelechargement(invoice)).thenReturn(LIEN);
        when(smsTemplates.smsFacture(any(), any(), any(), any())).thenReturn("message");
        doThrow(new ExternalApiException("La passerelle SMS FluidPay est injoignable."))
                .when(smsSender).envoyer(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(ExternalApiException.class);
        assertThat(invoice.getShareSmsSentAt()).isNull();
    }

    @Test
    @DisplayName("autre agence → facture introuvable")
    void aLaDemande_autreAgence() {
        when(invoiceRepository.findWithLockByIdAndBranchId(INVOICE_ID, BRANCH_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> notifier.envoyerALaDemande(INVOICE_ID, BRANCH_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

package com.labo.anapath.finance;

import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.exception.ResourceNotFoundException;
import com.labo.anapath.contract.Contrat;
import com.labo.anapath.contract.ContratRepository;
import com.labo.anapath.patient.PatientRepository;
import com.labo.anapath.setting.SettingInvoiceRepository;
import com.labo.anapath.test.LabTestRepository;
import com.labo.anapath.testorder.TestOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private LabTestRepository labTestRepository;
    @Mock private CashboxRepository cashboxRepository;
    @Mock private CashboxOperationRepository cashboxOperationRepository;
    @Mock private ContratRepository contratRepository;
    @Mock private RefundRequestRepository refundRequestRepository;
    @Mock private RefundReasonRepository refundReasonRepository;
    @Mock private SettingInvoiceRepository settingInvoiceRepository;
    @Mock private FinanceMapper financeMapper;

    @InjectMocks private InvoiceServiceImpl service;

    private static final UUID INVOICE_ID  = UUID.randomUUID();
    private static final UUID BRANCH_ID   = UUID.randomUUID();
    private static final UUID CONTRAT_ID  = UUID.randomUUID();

    private Invoice buildInvoice(int statusInvoice) {
        Invoice inv = new Invoice();
        inv.setBranchId(BRANCH_ID);
        inv.setTotal(new BigDecimal("5000.00"));
        inv.setPaid(false);
        inv.setStatusInvoice(statusInvoice);
        inv.setStatus(InvoiceStatus.PENDING);
        return inv;
    }

    @Test
    @DisplayName("markAsPaid - facture déjà payée → InvalidOperationException INVOICE_ALREADY_PAID")
    void markAsPaid_alreadyPaid_throws422() {
        Invoice inv = buildInvoice(0);
        inv.setPaid(true);

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("ESPECES");

        assertThatThrownBy(() -> service.markAsPaid(INVOICE_ID, dto, BRANCH_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("INVOICE_ALREADY_PAID");

        verify(cashboxRepository, never()).findFirstByBranchIdAndType(any(), any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAsPaid - vente (statusInvoice=0) → crédit caisse vente")
    void markAsPaid_vente_creditsCashboxVente() {
        Invoice inv = buildInvoice(0);
        Cashbox cashVente = new Cashbox();
        cashVente.setBranchId(BRANCH_ID);
        cashVente.setType("vente");
        cashVente.setBalance(new BigDecimal("10000.00"));

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));
        when(cashboxRepository.findFirstByBranchIdAndType(BRANCH_ID, "vente")).thenReturn(Optional.of(cashVente));
        when(cashboxRepository.save(cashVente)).thenReturn(cashVente);
        when(invoiceRepository.save(any())).thenReturn(inv);
        when(financeMapper.toInvoiceResponseDto(inv)).thenReturn(null);

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("ESPECES");

        service.markAsPaid(INVOICE_ID, dto, BRANCH_ID);

        ArgumentCaptor<Cashbox> captor = ArgumentCaptor.forClass(Cashbox.class);
        verify(cashboxRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(inv.getPaid()).isTrue();
        assertThat(inv.getPayment()).isEqualTo("ESPECES");
    }

    /**
     * Ce que le solde de fermeture additionne.
     *
     * <p>Le solde de la caisse montait bien sans qu'aucune opération ne soit
     * écrite : l'écran de fermeture, qui somme les opérations par mode de
     * paiement, affichait donc zéro malgré des dizaines de factures encaissées.
     * Vérifier le seul solde ne suffit pas — c'est ce que faisait le test
     * ci-dessus, et le défaut est passé.</p>
     */
    @Test
    @DisplayName("markAsPaid - vente → opération de caisse tracée avec son mode de paiement")
    void markAsPaid_vente_traceOperationDeCaisse() {
        Invoice inv = buildInvoice(0);
        Cashbox cashVente = new Cashbox();
        cashVente.setBranchId(BRANCH_ID);
        cashVente.setType("vente");
        cashVente.setBalance(new BigDecimal("10000.00"));

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));
        when(cashboxRepository.findFirstByBranchIdAndType(BRANCH_ID, "vente")).thenReturn(Optional.of(cashVente));
        when(invoiceRepository.save(any())).thenReturn(inv);
        when(financeMapper.toInvoiceResponseDto(inv)).thenReturn(null);

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("MOBILEMONEY");

        service.markAsPaid(INVOICE_ID, dto, BRANCH_ID);

        ArgumentCaptor<CashboxOperation> captor = ArgumentCaptor.forClass(CashboxOperation.class);
        verify(cashboxOperationRepository).save(captor.capture());
        CashboxOperation op = captor.getValue();
        assertThat(op.getType()).isEqualTo("CREDIT");
        assertThat(op.getAmount()).isEqualByComparingTo(inv.getTotal());
        // Le mode doit correspondre exactement aux clés sommées par l'écran de
        // fermeture (ESPECES, MOBILEMONEY, CHEQUES, VIREMENT).
        assertThat(op.getPaymentMethod()).isEqualTo("MOBILEMONEY");
        assertThat(op.getInvoiceId()).isEqualTo(inv.getId());
        assertThat(op.getCashbox()).isSameAs(cashVente);
    }

    @Test
    @DisplayName("markAsPaid - avoir → opération tracée au débit")
    void markAsPaid_avoir_traceOperationAuDebit() {
        Invoice inv = buildInvoice(1);
        Cashbox cashDepense = new Cashbox();
        cashDepense.setBranchId(BRANCH_ID);
        cashDepense.setType("depense");
        cashDepense.setBalance(new BigDecimal("10000.00"));

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));
        when(cashboxRepository.findFirstByBranchIdAndType(BRANCH_ID, "depense")).thenReturn(Optional.of(cashDepense));
        when(invoiceRepository.save(any())).thenReturn(inv);
        when(financeMapper.toInvoiceResponseDto(inv)).thenReturn(null);

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("ESPECES");

        service.markAsPaid(INVOICE_ID, dto, BRANCH_ID);

        ArgumentCaptor<CashboxOperation> captor = ArgumentCaptor.forClass(CashboxOperation.class);
        verify(cashboxOperationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("markAsPaid - avoir (statusInvoice=1) → débit caisse dépense")
    void markAsPaid_avoir_debitsCashboxDepense() {
        Invoice inv = buildInvoice(1);
        Cashbox cashDepense = new Cashbox();
        cashDepense.setBranchId(BRANCH_ID);
        cashDepense.setType("depense");
        cashDepense.setBalance(new BigDecimal("3000.00"));

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));
        when(cashboxRepository.findFirstByBranchIdAndType(BRANCH_ID, "depense")).thenReturn(Optional.of(cashDepense));
        when(cashboxRepository.save(cashDepense)).thenReturn(cashDepense);
        when(invoiceRepository.save(any())).thenReturn(inv);
        when(financeMapper.toInvoiceResponseDto(inv)).thenReturn(null);

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("VIREMENT");

        service.markAsPaid(INVOICE_ID, dto, BRANCH_ID);

        ArgumentCaptor<Cashbox> captor = ArgumentCaptor.forClass(Cashbox.class);
        verify(cashboxRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("-2000.00"));
    }

    @Test
    @DisplayName("markAsPaid - contrat invoiceUnique=true → isClose = true")
    void markAsPaid_invoiceUnique_closesContract() {
        Contrat contrat = new Contrat();
        contrat.setInvoiceUnique(true);
        contrat.setIsClose(false);

        Invoice inv = buildInvoice(0);
        inv.setContrat(contrat);

        Cashbox cashVente = new Cashbox();
        cashVente.setBranchId(BRANCH_ID);
        cashVente.setType("vente");
        cashVente.setBalance(BigDecimal.ZERO);

        when(invoiceRepository.findByIdAndBranchId(eq(INVOICE_ID), any())).thenReturn(Optional.of(inv));
        when(cashboxRepository.findFirstByBranchIdAndType(BRANCH_ID, "vente")).thenReturn(Optional.of(cashVente));
        when(cashboxRepository.save(any())).thenReturn(cashVente);
        when(invoiceRepository.save(any())).thenReturn(inv);
        when(financeMapper.toInvoiceResponseDto(inv)).thenReturn(null);

        InvoiceStatusUpdateDto dto = new InvoiceStatusUpdateDto();
        dto.setPayment("MOBILEMONEY");

        service.markAsPaid(INVOICE_ID, dto, BRANCH_ID);

        assertThat(contrat.getIsClose()).isTrue();
        verify(contratRepository).save(contrat);
    }

    @Test
    @DisplayName("getBusinessDashboard - retourne les 3 totaux corrects")
    void getBusinessDashboard_returnsThreeTotals() {
        LocalDate today = LocalDate.now();
        int currentMonth = today.getMonthValue();
        int currentYear = today.getYear();
        int lastMonth = today.minusMonths(1).getMonthValue();
        int lastYear = today.minusMonths(1).getYear();

        when(invoiceRepository.sumPaidByBranchIdAndDate(BRANCH_ID, today))
                .thenReturn(new BigDecimal("2000.00"));
        when(invoiceRepository.sumPaidByBranchIdAndMonth(BRANCH_ID, currentMonth, currentYear))
                .thenReturn(new BigDecimal("50000.00"));
        when(invoiceRepository.sumPaidByBranchIdAndMonth(BRANCH_ID, lastMonth, lastYear))
                .thenReturn(new BigDecimal("45000.00"));

        BusinessDashboardDto result = service.getBusinessDashboard(BRANCH_ID);

        assertThat(result.totalToday()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(result.totalMonth()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(result.totalLastMonth()).isEqualByComparingTo(new BigDecimal("45000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rapport sur une période
    // ─────────────────────────────────────────────────────────────────────

    /** Ligne telle que la rend une requête native : {année, mois, total}. */
    private static Object[] ligneMois(int annee, int mois, String total) {
        return new Object[]{annee, mois, new BigDecimal(total)};
    }

    @Test
    @DisplayName("Période : un mois sans facture apparaît à zéro plutôt que de manquer")
    void periodeCombleLesMoisVides() {
        // Ventes en janvier et mars seulement — février est muet côté base.
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), eq(0)))
                .thenReturn(List.<Object[]>of(ligneMois(2026, 1, "10000"), ligneMois(2026, 3, "30000")));
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), eq(1)))
                .thenReturn(List.of());
        when(invoiceRepository.sumMonthlyPaidInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());
        when(invoiceRepository.sumByContractInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());

        InvoiceReportDto rapport = service.getReportsForPeriod(
                BRANCH_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        assertThat(rapport.months()).hasSize(3);
        assertThat(rapport.months()).extracting(InvoiceReportDto.MonthlyRow::label)
                .containsExactly("Janvier 2026", "Février 2026", "Mars 2026");
        assertThat(rapport.months().get(1).sales()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rapport.totalSales()).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    @DisplayName("Période : le chiffre d'affaires retranche les avoirs, mois par mois et au total")
    void periodeCalculeLeChiffreDAffaires() {
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), eq(0)))
                .thenReturn(List.<Object[]>of(ligneMois(2026, 5, "50000")));
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), eq(1)))
                .thenReturn(List.<Object[]>of(ligneMois(2026, 5, "8000")));
        when(invoiceRepository.sumMonthlyPaidInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.<Object[]>of(ligneMois(2026, 6, "12000")));
        when(invoiceRepository.sumByContractInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());

        InvoiceReportDto rapport = service.getReportsForPeriod(
                BRANCH_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30));

        assertThat(rapport.months().get(0).turnover()).isEqualByComparingTo(new BigDecimal("42000"));
        assertThat(rapport.turnover()).isEqualByComparingTo(new BigDecimal("42000"));
        // L'encaissement tombe en juin alors que la vente est de mai : c'est le
        // décalage d'`updated_at`, faute de date de règlement dans la table.
        assertThat(rapport.months().get(1).collections()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(rapport.collections()).isEqualByComparingTo(new BigDecimal("12000"));
    }

    @Test
    @DisplayName("Période : la borne haute passée au dépôt est exclusive, au lendemain à 00:00")
    void periodeBorneHauteExclusive() {
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(invoiceRepository.sumMonthlyPaidInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());
        when(invoiceRepository.sumByContractInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());

        service.getReportsForPeriod(BRANCH_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ArgumentCaptor<LocalDateTime> debut = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> fin = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(invoiceRepository, atLeastOnce()).sumMonthlyByStatusInPeriod(
                eq(BRANCH_ID), debut.capture(), fin.capture(), anyInt());

        assertThat(debut.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        // 1er septembre et non 31 août : une facture créée le 31 à 14h serait
        // sinon exclue du mois auquel elle appartient.
        assertThat(fin.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    @Test
    @DisplayName("Période : un mois civil entier s'annonce par son nom, pas par ses bornes")
    void periodeMoisEntierPrendLeLibelleDuMois() {
        when(invoiceRepository.sumMonthlyByStatusInPeriod(eq(BRANCH_ID), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(invoiceRepository.sumMonthlyPaidInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());
        when(invoiceRepository.sumByContractInPeriod(eq(BRANCH_ID), any(), any()))
                .thenReturn(List.of());

        assertThat(service.getReportsForPeriod(
                BRANCH_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).period())
                .isEqualTo("Août 2026");

        assertThat(service.getReportsForPeriod(
                BRANCH_ID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)).period())
                .doesNotContain("Août 2026");
    }

    @Test
    @DisplayName("Période : des bornes inversées sont refusées, pas silencieusement vidées")
    void periodeRefuseLesBornesInversees() {
        assertThatThrownBy(() -> service.getReportsForPeriod(
                BRANCH_ID, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(InvalidOperationException.class);

        assertThatThrownBy(() -> service.getReportsForPeriod(BRANCH_ID, null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(InvalidOperationException.class);
    }
}

package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.integration.InvoiceGeneratedEvent;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly invoice run invoices each merchant in its OWN transaction. It used
 * to run every merchant in one, so one merchant whose invoice could not be
 * written rolled back every invoice of the night. Pins: the failing merchant is
 * rolled back alone, counted on {@code loyalty.invoice.run.failed} and logged;
 * the others commit and each fire their own event; nothing already invoiced is
 * written again.
 *
 * <p>Pure Mockito with a recording transaction manager, so the transaction
 * boundaries themselves are what is asserted.
 */
class InvoicingRunIsolationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RecordingTxManager tx = new RecordingTxManager();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final InvoicingService service = new InvoicingService(invoices, merchants, transactions, vouchers,
            rules, new LoyaltyProperties(null, null, null, null, null, null, null), null, events, tx, meters);

    private final Merchant alpha = merchant("Alpha");
    private final Merchant beta = merchant("Beta");
    private final Merchant gamma = merchant("Gamma");

    /** Records what each transaction did, in order: "begin(ro)", "commit", "rollback". */
    static final class RecordingTxManager implements PlatformTransactionManager {
        final List<String> log = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            log.add(definition != null && definition.isReadOnly() ? "begin(ro)" : "begin");
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            log.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            log.add("rollback");
        }
    }

    private static Merchant merchant(String name) {
        Merchant m = new Merchant();
        m.setId(UUID.randomUUID());
        m.setTenantId(TENANT);
        m.setName(name);
        m.setCurrency("USD");
        m.setBillingCycle(Merchant.BillingCycle.DAILY);
        // An explicit merchant-record fee: $0.50 per voucher issued.
        m.setFeeIssuedType(Merchant.FeeType.FIXED);
        m.setFeeIssuedFixed(new BigDecimal("0.50"));
        return m;
    }

    private static Object[] issued(Merchant m) {
        return new Object[]{m.getId(), Instant.parse("2026-10-05T10:00:00Z"), new BigDecimal("10.00")};
    }

    @BeforeEach
    void stubTheHappyPath() {
        when(merchants.findAll()).thenReturn(List.of(alpha, beta, gamma));
        when(vouchers.issuedFaceValuesBetween(any(), any(), any()))
                .thenReturn(List.<Object[]>of(issued(alpha), issued(beta), issued(gamma)));
        when(invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(any(), any(), any())).thenReturn(Optional.empty());
        when(transactions.sumPointsIssued(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumPointsRedeemed(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(invoices.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            if (beta.getId().equals(i.getMerchantId())) {
                throw new DataIntegrityViolationException("value too long for type character varying(8)");
            }
            i.setId(UUID.randomUUID());
            return i;
        });
    }

    private double failures() {
        return meters.counter(InvoicingService.RUN_FAILED_METRIC).count();
    }

    @Test
    void theFailureCounterExistsAtZeroBeforeAnythingFails() {
        assertThat(meters.find(InvoicingService.RUN_FAILED_METRIC).counter()).isNotNull();
        assertThat(failures()).isZero();
    }

    @Test
    void oneMerchantFailing_rollsBackOnlyThatMerchant_andTheOthersAreStillInvoiced() {
        int created = service.runPeriodicForAllMerchants(TODAY);

        assertThat(created).isEqualTo(2);
        assertThat(failures()).isEqualTo(1.0);

        // One read-only pre-load, then one transaction per billable merchant:
        // Alpha commits, Beta rolls back alone, Gamma still commits.
        assertThat(tx.log).containsExactly(
                "begin(ro)", "commit",
                "begin", "commit",
                "begin", "rollback",
                "begin", "commit");

        // The stamp ran for the two merchants that were written, never for Beta.
        verify(transactions).stampInvoice(any(), eq(alpha.getId()), any(), any());
        verify(transactions).stampInvoice(any(), eq(gamma.getId()), any(), any());
        verify(transactions, never()).stampInvoice(any(), eq(beta.getId()), any(), any());

        // Each committed merchant fires its own event (AFTER_COMMIT of its own transaction).
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues())
                .extracting(e -> ((InvoiceGeneratedEvent) e).merchantId())
                .containsExactly(alpha.getId(), gamma.getId());
    }

    @Test
    void theInvoiceCarriesThePreloadedBilling() {
        service.runPeriodicForAllMerchants(TODAY);

        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices, times(3)).save(saved.capture());
        Invoice alphaInvoice = saved.getAllValues().get(0);
        assertThat(alphaInvoice.getMerchantId()).isEqualTo(alpha.getId());
        assertThat(alphaInvoice.getPeriodStart()).isEqualTo(YESTERDAY);
        assertThat(alphaInvoice.getPeriodEnd()).isEqualTo(YESTERDAY);
        assertThat(alphaInvoice.getVouchersIssued()).isEqualTo(1);
        assertThat(alphaInvoice.getVouchersRedeemed()).isZero();
        assertThat(alphaInvoice.getTotalAmount()).isEqualByComparingTo("0.50");
    }

    @Test
    void aMerchantAlreadyInvoicedForThePeriod_isSkippedWithoutATransaction() {
        when(invoices.findMerchantIdsInvoicedFor(any(), eq(YESTERDAY), eq(YESTERDAY)))
                .thenReturn(List.of(alpha.getId(), beta.getId()));

        int created = service.runPeriodicForAllMerchants(TODAY);

        assertThat(created).isEqualTo(1);
        assertThat(failures()).isZero();
        assertThat(tx.log).containsExactly("begin(ro)", "commit", "begin", "commit");
        verify(invoices, times(1)).save(any(Invoice.class));
    }

    @Test
    void anInvoiceThatLandedAfterThePreload_isNotWrittenTwice() {
        // e.g. a manual POST /loyalty/invoices/generate between the pre-load and
        // this merchant's turn: the in-transaction re-check finds it.
        when(invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(eq(gamma.getId()), eq(YESTERDAY), eq(YESTERDAY)))
                .thenReturn(Optional.of(new Invoice()));

        int created = service.runPeriodicForAllMerchants(TODAY);

        assertThat(created).isEqualTo(1);   // Alpha; Beta failed; Gamma already had one
        verify(transactions, never()).stampInvoice(any(), eq(gamma.getId()), any(), any());
        verify(transactions, never()).sumPointsIssued(eq(gamma.getId()), any(), any());
    }

    @Test
    void aMerchantWithNothingToBill_costsNoTransactionAtAll() {
        when(vouchers.issuedFaceValuesBetween(any(), any(), any())).thenReturn(List.<Object[]>of(issued(alpha)));
        when(invoices.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        int created = service.runPeriodicForAllMerchants(TODAY);

        assertThat(created).isEqualTo(1);
        assertThat(tx.log).containsExactly("begin(ro)", "commit", "begin", "commit");
        verify(invoices, never()).findByMerchantIdAndPeriodStartAndPeriodEnd(eq(beta.getId()), any(), any());
    }

    @Test
    void inactiveMerchantsAreNotInvoiced() {
        beta.setStatus(Merchant.Status.INACTIVE);
        gamma.setStatus(Merchant.Status.INACTIVE);

        assertThat(service.runPeriodicForAllMerchants(TODAY)).isEqualTo(1);
        verify(invoices, times(1)).save(any(Invoice.class));
    }
}

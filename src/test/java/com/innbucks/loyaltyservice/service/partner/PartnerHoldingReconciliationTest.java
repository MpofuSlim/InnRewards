package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.scheduler.PartnerHoldingReconciliationJob;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation.Line;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation.Snapshot;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation.Status;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Balances;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Money;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Stuck;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconciliation's arithmetic, and the bank comparison no provider can feed
 * yet (the Postgres IT covers the figures themselves).
 */
class PartnerHoldingReconciliationTest {

    /** Paid in 100, settled 30: 70 expected, held as 40 unspent + 5 expired + 15 owed + 10 not issued. */
    private static Snapshot balanced() {
        return new Snapshot(
                Map.of("USD", money("100")), Map.of("USD", money("30")), Map.of("USD", money("15")),
                Map.of("USD", new Balances(money("40"), money("5"))), Map.of("USD", money("10")),
                Map.of("USD", money("8")), Map.of("USD", new Stuck(1, 2, 3, 4)));
    }

    @Test
    void recordsThatAddUp_areBalanced_andNothingFromTheBankIsInvented() {
        Line l = PartnerHoldingReconciliation.line("USD", balanced(), Optional.empty());

        assertThat(l.expectedHolding()).isEqualByComparingTo("70");
        assertThat(l.ledgerDifference()).isEqualByComparingTo("0");
        assertThat(l.fundingInFlight()).isEqualByComparingTo("8");
        assertThat(l.bankBalance()).isNull();
        assertThat(l.bankDifference()).isNull();
        assertThat(l.status()).isEqualTo(Status.BALANCED);
        // Stuck items are counted, but never change the status: they are not a difference.
        assertThat(List.of(l.stuckFunding(), l.stuckSettlements(), l.stuckPayments(), l.paidWithoutVoucher()))
                .containsExactly(1, 2, 3, 4);
        assertThat(l.expectedHolding().scale()).isEqualTo(4);
    }

    @Test
    void aMissingAmount_isALedgerMismatch_whateverTheBankSays() {
        Snapshot s = balanced();
        Snapshot lostOne = new Snapshot(s.paidIn(), s.settledOut(), s.owed(),
                Map.of("USD", new Balances(money("39"), money("5"))), s.paidNotIssued(), s.inFlight(), s.stuck());

        Line l = PartnerHoldingReconciliation.line("USD", lostOne, Optional.of(new BigDecimal("70")));

        assertThat(l.ledgerDifference()).isEqualByComparingTo("1");
        assertThat(l.bankDifference()).isEqualByComparingTo("0");
        assertThat(l.status()).isEqualTo(Status.LEDGER_MISMATCH);
    }

    @Test
    void theBankHoldingOtherThanExpected_isABankMismatch() {
        Line l = PartnerHoldingReconciliation.line("USD", balanced(), Optional.of(new BigDecimal("69.50")));

        assertThat(l.bankDifference()).isEqualByComparingTo("-0.50");
        assertThat(l.status()).isEqualTo(Status.BANK_MISMATCH);
        assertThat(PartnerHoldingReconciliation.line("USD", balanced(), Optional.of(new BigDecimal("70.0000")))
                .status()).isEqualTo(Status.BALANCED);
    }

    @Test
    void aCurrencyWithNoRecords_isAllZeroAndBalanced() {
        Line l = PartnerHoldingReconciliation.line("ZWG", balanced(), Optional.empty());

        assertThat(l.paidIn()).isEqualByComparingTo("0");
        assertThat(l.expectedHolding()).isEqualByComparingTo("0");
        assertThat(l.status()).isEqualTo(Status.BALANCED);
        assertThat(l.stuckSettlements()).isZero();
    }

    @Test
    void theRunIsAsBadAsItsWorstCurrency() {
        Line ok = PartnerHoldingReconciliation.line("USD", balanced(), Optional.empty());
        Line bank = PartnerHoldingReconciliation.line("USD", balanced(), Optional.of(BigDecimal.ONE));
        Snapshot s = balanced();
        Line ledger = PartnerHoldingReconciliation.line("USD", new Snapshot(s.paidIn(), Map.of(), s.owed(),
                s.balances(), s.paidNotIssued(), s.inFlight(), s.stuck()), Optional.empty());

        assertThat(PartnerHoldingReconciliation.worst(List.of())).isEqualTo(Status.BALANCED);
        assertThat(PartnerHoldingReconciliation.worst(List.of(ok, bank))).isEqualTo(Status.BANK_MISMATCH);
        assertThat(PartnerHoldingReconciliation.worst(List.of(bank, ledger, ok))).isEqualTo(Status.LEDGER_MISMATCH);
    }

    @Test
    void theDailyJob_runsOnlyWhereThePartnerApiIsOn_andCanBeSwitchedOff() {
        PartnerHoldingReconciliation reconciliation = mock(PartnerHoldingReconciliation.class);
        VoucherPartnerProperties off = props(false);
        VoucherPartnerProperties on = props(true);

        new PartnerHoldingReconciliationJob(off, reconciliation, true).reconcile();
        new PartnerHoldingReconciliationJob(on, reconciliation, false).reconcile();
        verify(reconciliation, never()).run(any(), any());

        when(reconciliation.run(any(), any())).thenThrow(new IllegalStateException("database down"));
        // A failure is logged, never thrown out of the scheduler thread.
        new PartnerHoldingReconciliationJob(on, reconciliation, true).reconcile();
        verify(reconciliation).run(PartnerHoldingReconciliation.Trigger.SCHEDULED, null);
    }

    private static VoucherPartnerProperties props(boolean enabled) {
        return new VoucherPartnerProperties(enabled, "aud", "NONE", null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), 1);
    }
}

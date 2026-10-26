package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingLeg;
import com.innbucks.loyaltyservice.service.partner.VoucherFundingProvider.FundingOutcome.Kind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Staging's simulated provider gives the middleware every outcome to test against (V62). */
class SimulatedVoucherFundingProviderTest {

    private final Instant start = Instant.parse("2026-10-26T10:00:00Z");

    private SimulatedVoucherFundingProvider at(Instant now) {
        return new SimulatedVoucherFundingProvider(Duration.ofSeconds(20), Duration.ofMinutes(10),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private FundingLeg leg(String reference, String method) {
        return new FundingLeg(UUID.randomUUID(), reference, UUID.randomUUID(), method, new BigDecimal("20.00"), "USD",
                "+263771234567", null, start);
    }

    @Test
    void merchantCollectedMoneyConfirmsAtOnce() {
        for (String method : new String[]{"CASH", "CARD_SWIPE", "MERCHANT_COLLECTED"}) {
            assertThat(at(start).fund(leg("MWOS-1", method)).kind()).as(method).isEqualTo(Kind.CONFIRMED);
        }
        assertThat(at(start).simulated()).isTrue();
        assertThat(at(start).available()).isTrue();
    }

    @Test
    void anInnbucksCodeWaitsForTheCustomer_thenCountsAsPaid() {
        var awaiting = at(start).fund(leg("MWOS-2", "INNBUCKS_CODE"));
        assertThat(awaiting.kind()).isEqualTo(Kind.AWAITING);
        assertThat(awaiting.paymentCode()).matches("\\d{9}");
        assertThat(awaiting.paymentExpiresAt()).isEqualTo(start.plus(Duration.ofMinutes(10)));

        assertThat(at(start.plusSeconds(5)).status(leg("MWOS-2", "INNBUCKS_CODE")).kind()).isEqualTo(Kind.PENDING);
        assertThat(at(start.plusSeconds(21)).status(leg("MWOS-2", "INNBUCKS_CODE")).kind()).isEqualTo(Kind.CONFIRMED);
    }

    @Test
    void theReferencePrefixesAskForEachOtherOutcome() {
        var fail = at(start).fund(leg("SIMFAIL-1", "CASH"));
        assertThat(fail.kind()).isEqualTo(Kind.FAILED);
        assertThat(fail.failureCode()).isEqualTo("FLOAT_INSUFFICIENT");

        assertThat(at(start).fund(leg("simdown-1", "CASH")).kind()).isEqualTo(Kind.NOT_SENT);

        assertThat(at(start).fund(leg("SIMSLOW-1", "CASH")).kind()).isEqualTo(Kind.PENDING);
        assertThat(at(start.plusSeconds(21)).status(leg("SIMSLOW-1", "CASH")).kind()).isEqualTo(Kind.CONFIRMED);

        assertThat(at(start).fund(leg("SIMSLOWFAIL-1", "CASH")).kind()).isEqualTo(Kind.PENDING);
        var slowFail = at(start.plusSeconds(21)).status(leg("SIMSLOWFAIL-1", "CASH"));
        assertThat(slowFail.kind()).isEqualTo(Kind.FAILED);
        assertThat(slowFail.failureCode()).isEqualTo("PAYMENT_DECLINED");

        var unpaidCode = at(start.plusSeconds(21)).status(leg("SIMFAIL-2", "INNBUCKS_CODE"));
        assertThat(unpaidCode.failureCode()).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    void redemptionsSettleAtOnce() {
        UUID id = UUID.randomUUID();
        var settled = at(start).settle(new VoucherFundingProvider.SettlementLeg(id, UUID.randomUUID(),
                new BigDecimal("5.00"), "USD", "R-1"));
        assertThat(settled.settled()).isTrue();
        assertThat(settled.providerRef()).isEqualTo("SIM-SETTLE-" + id);
    }

    @Test
    void withNoProviderNothingIsAvailable() {
        UnavailableVoucherFundingProvider none = new UnavailableVoucherFundingProvider();
        assertThat(none.available()).isFalse();
        assertThat(none.simulated()).isFalse();
        assertThat(none.settle(new VoucherFundingProvider.SettlementLeg(UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "USD", null)).settled()).isFalse();
    }
}

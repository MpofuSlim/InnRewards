package com.innbucks.loyaltyservice.service.partner;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * STAGING ONLY ({@code loyalty.voucher-partner.funding: SIMULATED}): every leg
 * "moves" money without any money moving, so the middleware can test generate,
 * inquire and redeem end to end before the real provider exists.
 *
 * <p>By default a leg succeeds: a cash, card-swipe or merchant-collected sale
 * confirms at once; an InnBucks code is returned to show, and counts as paid
 * {@code simulatedDelay} later. The partner can ask for the other outcomes by
 * starting its {@code partnerReference} with:
 * <ul>
 *   <li>{@code SIMFAIL-}: refused (FLOAT_INSUFFICIENT for money the merchant
 *       collected, INSUFFICIENT_FUNDS for an account debit, PAYMENT_EXPIRED for
 *       an InnBucks code, once the delay has passed);</li>
 *   <li>{@code SIMSLOW-}: PROCESSING, then confirmed after the delay;</li>
 *   <li>{@code SIMSLOWFAIL-}: PROCESSING, then PAYMENT_DECLINED after the delay;</li>
 *   <li>{@code SIMDOWN-}: the provider never received it (503
 *       PROVIDER_UNAVAILABLE).</li>
 * </ul>
 * Redemptions always settle at once.
 */
public class SimulatedVoucherFundingProvider implements VoucherFundingProvider {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Duration delay;
    private final Duration codeTtl;
    private final Clock clock;

    public SimulatedVoucherFundingProvider(Duration delay, Duration codeTtl, Clock clock) {
        this.delay = delay;
        this.codeTtl = codeTtl;
        this.clock = clock;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public FundingOutcome fund(FundingLeg leg) {
        String ref = upper(leg.partnerReference());
        if (ref.startsWith("SIMDOWN-")) {
            return FundingOutcome.notSent();
        }
        if (ref.startsWith("SIMSLOW-") || ref.startsWith("SIMSLOWFAIL-")) {
            return FundingOutcome.pending();
        }
        if ("INNBUCKS_CODE".equals(leg.method())) {
            String code = String.format("%09d", RANDOM.nextInt(1_000_000_000));
            return FundingOutcome.awaiting(code, clock.instant().plus(codeTtl));
        }
        if (ref.startsWith("SIMFAIL-")) {
            return FundingOutcome.failed(declineFor(leg.method()));
        }
        return FundingOutcome.confirmed(simRef(leg));
    }

    @Override
    public FundingOutcome status(FundingLeg leg) {
        String ref = upper(leg.partnerReference());
        Instant started = leg.startedAt() == null ? clock.instant() : leg.startedAt();
        if (clock.instant().isBefore(started.plus(delay))) {
            return FundingOutcome.pending();
        }
        if (ref.startsWith("SIMSLOWFAIL-")) {
            return FundingOutcome.failed("PAYMENT_DECLINED");
        }
        if (ref.startsWith("SIMFAIL-")) {
            return FundingOutcome.failed(declineFor(leg.method()));
        }
        return FundingOutcome.confirmed(simRef(leg));
    }

    @Override
    public SettlementOutcome settle(SettlementLeg leg) {
        return SettlementOutcome.settledAs("SIM-SETTLE-" + leg.redemptionId());
    }

    private static String declineFor(String method) {
        return switch (method) {
            case "INNBUCKS_CODE" -> "PAYMENT_EXPIRED";
            case "INNBUCKS_ACCOUNT" -> "INSUFFICIENT_FUNDS";
            default -> "FLOAT_INSUFFICIENT";
        };
    }

    private static String simRef(FundingLeg leg) {
        return "SIM-FUND-" + leg.orderId();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}

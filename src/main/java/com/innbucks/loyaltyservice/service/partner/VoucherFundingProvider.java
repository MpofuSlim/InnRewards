package com.innbucks.loyaltyservice.service.partner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Moves a partner voucher's money (V62): its value INTO the holding account
 * before the voucher exists, and each redemption's amount OUT of holding to the
 * merchant. A code paid at the redeem (V69) is one leg from the agent straight
 * to the issuing merchant ({@link Destination#MERCHANT}), with nothing held. One port, so the provider behind it (simulated on staging, BR or
 * Veengu through payment-service later) changes nothing above it.
 *
 * <p>The rules every implementation keeps, from the fleet's payment rails:
 * <ul>
 *   <li>{@link #fund} and {@link #settle} move money and are NEVER retried by
 *       the caller; {@link #status} only reads and may be;</li>
 *   <li>each leg carries OUR reference (the order or redemption id), which is
 *       the provider-side idempotency key and what {@link #status} looks up;</li>
 *   <li>an outcome that is not known is {@link FundingOutcome.Kind#PENDING},
 *       never guessed: the reconciler asks again.</li>
 * </ul>
 */
public interface VoucherFundingProvider {

    /** False when no provider is configured: generate refuses before recording anything. */
    boolean available();

    /** True when no money really moves (staging). Shown to the partner as {@code funding.simulated}. */
    boolean simulated();

    /** Start the leg into holding. Never retried by the caller. */
    FundingOutcome fund(FundingLeg leg);

    /** Read where a started leg stands. Safe to repeat. */
    FundingOutcome status(FundingLeg leg);

    /** Pay one redemption's amount from holding to the merchant. Idempotent by the redemption id. */
    SettlementOutcome settle(SettlementLeg leg);

    /**
     * The holding account's balance in {@code currency}, as the provider holds it,
     * for the daily reconciliation. Empty when the provider cannot say (no
     * provider, the simulator): the reconciliation then checks our own records
     * only and stores no bank figure. A read; safe to repeat.
     */
    default java.util.Optional<BigDecimal> holdingBalance(String currency) {
        return java.util.Optional.empty();
    }

    /**
     * One funding leg, keyed by {@code orderId}: our order id for a sale paid at
     * generate (into holding), or, for a code paid at the redeem (V69), the
     * payment attempt's id (straight to the issuing merchant). That id is the
     * provider-side idempotency key either way.
     */
    record FundingLeg(UUID orderId, String partnerReference, UUID merchantId, String method,
                      BigDecimal amount, String currency, String customerPhone, String accountNumber,
                      Instant startedAt, Destination destination) {

        /** A leg into holding (every leg before V69). */
        public FundingLeg(UUID orderId, String partnerReference, UUID merchantId, String method,
                          BigDecimal amount, String currency, String customerPhone, String accountNumber,
                          Instant startedAt) {
            this(orderId, partnerReference, merchantId, method, amount, currency, customerPhone, accountNumber,
                    startedAt, Destination.HOLDING);
        }
    }

    /** Where a funding leg pays to. */
    enum Destination {
        /** Flow 1: the value is held until the voucher is used. */
        HOLDING,
        /** Flow 2 (V69): the agent's payment goes straight to the issuing merchant. */
        MERCHANT
    }

    record FundingOutcome(Kind kind, String providerRef, String failureCode, String paymentCode,
                          Instant paymentExpiresAt) {

        public enum Kind {
            /** The value is in holding. */
            CONFIRMED,
            /** Definitely refused; nothing moved. {@code failureCode} says why. */
            FAILED,
            /** The provider never received it; nothing moved. The attempt is closed. */
            NOT_SENT,
            /** Sent, outcome not known yet. */
            PENDING,
            /** The customer must still pay ({@code paymentCode} until {@code paymentExpiresAt}). */
            AWAITING
        }

        public static FundingOutcome confirmed(String providerRef) {
            return new FundingOutcome(Kind.CONFIRMED, providerRef, null, null, null);
        }

        public static FundingOutcome failed(String failureCode) {
            return new FundingOutcome(Kind.FAILED, null, failureCode, null, null);
        }

        public static FundingOutcome notSent() {
            return new FundingOutcome(Kind.NOT_SENT, null, "PROVIDER_UNAVAILABLE", null, null);
        }

        public static FundingOutcome pending() {
            return new FundingOutcome(Kind.PENDING, null, null, null, null);
        }

        public static FundingOutcome awaiting(String paymentCode, Instant expiresAt) {
            return new FundingOutcome(Kind.AWAITING, null, null, paymentCode, expiresAt);
        }
    }

    /** One redemption's payment from holding to the merchant, keyed by the redemption id. */
    record SettlementLeg(UUID redemptionId, UUID merchantId, BigDecimal amount, String currency,
                         String reference) {}

    record SettlementOutcome(boolean settled, String providerRef) {
        public static SettlementOutcome settledAs(String providerRef) {
            return new SettlementOutcome(true, providerRef);
        }

        public static SettlementOutcome notYet() {
            return new SettlementOutcome(false, null);
        }
    }
}

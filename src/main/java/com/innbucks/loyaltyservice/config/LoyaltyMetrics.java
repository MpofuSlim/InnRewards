package com.innbucks.loyaltyservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Business-level metrics for the loyalty domain. Spring Boot Actuator already
 * surfaces infrastructure metrics (HTTP latency per endpoint, JVM, Hikari,
 * Tomcat) — these counters add the loyalty-specific signals: how many points
 * moved, how many vouchers issued and redeemed, where fraud was rejected.
 *
 * <p>All names use the {@code loyalty.} prefix so they're easy to dashboard
 * and alert on separately from the Spring defaults. Available at
 * {@code /actuator/prometheus} in the standard exposition format.
 *
 * <p>The MeterRegistry is auto-wired by Spring Boot (a PrometheusMeterRegistry
 * because {@code management.endpoints.web.exposure.include} has {@code prometheus}).
 * Counter increments are nanosecond-scale atomic operations — they can be
 * dropped on hot paths without measurable cost.
 */
@Component
public class LoyaltyMetrics {

    private final MeterRegistry registry;

    private final Counter vouchersIssued;
    private final Counter vouchersRedeemed;
    private final Counter pendingPromoted;
    private final Counter pointsEarned;
    private final Counter pointsRedeemed;
    private final Counter pointsExpired;
    private final Counter reconciliationDrift;
    private final Counter reconciliationRepaired;
    private final Timer redemptionLatency;

    public LoyaltyMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.vouchersIssued = Counter.builder("loyalty.voucher.issued")
                .description("Total vouchers issued (single + bulk)")
                .register(registry);
        this.vouchersRedeemed = Counter.builder("loyalty.voucher.redeemed")
                .description("Total successful voucher redemptions")
                .register(registry);
        this.pendingPromoted = Counter.builder("loyalty.user.promoted")
                .description("Total PENDING LoyaltyUsers flipped to ACTIVE by the registration webhook")
                .register(registry);
        // Volume counters — increment by the points amount, not 1. Pairs with
        // loyalty.transaction.posted (which counts events). rate() over points
        // earned answers "are we awarding points faster than usual?" — a sudden
        // 10x spike on points.earned with flat .posted means the rules engine
        // is mis-evaluating multipliers.
        this.pointsEarned = Counter.builder("loyalty.points.earned")
                .description("Sum of points credited to customer wallets (PURCHASE/QR_PAY rules)")
                .baseUnit("points")
                .register(registry);
        this.pointsRedeemed = Counter.builder("loyalty.points.redeemed")
                .description("Sum of points debited from customer wallets (REDEMPTION)")
                .baseUnit("points")
                .register(registry);
        // Breakage: points released by expiry. A material financial figure
        // (expired points = released liability) and a churn signal.
        this.pointsExpired = Counter.builder("loyalty.points.expired")
                .description("Sum of points released by expiry (breakage)")
                .baseUnit("points")
                .register(registry);
        // Integrity signal: wallets whose cached balance drifted from the
        // ledger (the source of truth). The invariant is zero drift, so ANY
        // increase is page-worthy — alert on increase(loyalty_reconciliation_drift_total[2d]) > 0.
        // If auto-fix is off, the same stuck wallet re-counts each daily run,
        // which is the intended "persistent unrepaired drift" escalation.
        this.reconciliationDrift = Counter.builder("loyalty.reconciliation.drift")
                .description("Wallets found with balance != sum(ledger delta) by the daily reconciliation job")
                .baseUnit("wallets")
                .register(registry);
        this.reconciliationRepaired = Counter.builder("loyalty.reconciliation.repaired")
                .description("Drifting wallets whose balance was rebuilt from the ledger (auto-fix enabled)")
                .baseUnit("wallets")
                .register(registry);
        // Percentile histogram lets dashboards graph p50/p95/p99 for the
        // single most performance-sensitive call in the service.
        this.redemptionLatency = Timer.builder("loyalty.voucher.redeem.latency")
                .description("End-to-end voucher redemption latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incVouchersIssued() {
        vouchersIssued.increment();
    }

    public void incVouchersIssued(long count) {
        if (count > 0) vouchersIssued.increment(count);
    }

    public void incVouchersRedeemed() {
        vouchersRedeemed.increment();
    }

    public void incPendingPromoted(int count) {
        if (count > 0) pendingPromoted.increment(count);
    }

    /**
     * Counter for transactions grouped by type. We resolve the meter lazily
     * (one tag value per TransactionType) so callers don't need to know which
     * tag values exist; new types added later are surfaced automatically.
     */
    public void incTransactionPosted(String type) {
        Counter.builder("loyalty.transaction.posted")
                .description("Transactions posted to the ledger, grouped by type")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * Phones registered (their owner proved they hold the number), tagged by
     * which proof did it — TICKETING_OTP / PARTNER_ASSERTION / PARTNER_KEY.
     *
     * <p>Worth alerting on: a sustained rise in
     * {@code loyalty_phone_registered_total{source="PARTNER_ASSERTION"}} well
     * above the app's real signup + login rate is what a leaked signing key
     * looks like from here.
     */
    public void incPhoneRegistered(String source) {
        Counter.builder("loyalty.phone.registered")
                .description("Phones recorded as registered, grouped by the proof that registered them")
                .tag("source", source)
                .register(registry)
                .increment();
    }

    /**
     * Rejections at the partner registration endpoint, grouped by reason
     * (bad_key, bad_assertion, unconfigured, bad_phone). Brute-force attempts
     * against the shared key show up here before they show up anywhere else.
     */
    public void incPartnerRegistrationRejected(String reason) {
        Counter.builder("loyalty.partner.registration.rejected")
                .description("Partner registration calls rejected, grouped by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * Loyalty session lifecycle events (V43), tagged by outcome — {@code started}
     * (a refresh chain opened), {@code refreshed} (a rotation), {@code signed_out},
     * {@code revoked}.
     *
     * <p>{@code refreshed} is the load-bearing one for capacity: it is the
     * number of SMS OTPs the refresh path SAVED, since every refresh is a
     * re-proof that did not have to happen.
     */
    public void incLoyaltySession(String outcome) {
        Counter.builder("loyalty.session.event")
                .description("Loyalty session lifecycle events, grouped by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /**
     * Refused refresh attempts, grouped by reason (unknown, revoked, expired,
     * registration_revoked, reuse_detected).
     *
     * <p><b>Alert on {@code reuse_detected}.</b> It means two parties presented
     * credentials from one chain — a stolen refresh token being used alongside
     * the customer's own device. It should be zero; anything above a trickle is
     * an incident, not noise. The others are ordinary lapsed sessions.
     */
    public void incLoyaltySessionRejected(String reason) {
        Counter.builder("loyalty.session.rejected")
                .description("Loyalty session refresh attempts refused, grouped by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * Counter for fraud rejections grouped by reason. Spike alerts on this
     * (e.g. rate(loyalty_fraud_rejected_total{reason="BAD_SIGNATURE"}[5m]) > 1)
     * are the cheapest possible early-warning for attacks.
     */
    public void incFraudRejected(String reason) {
        Counter.builder("loyalty.fraud.rejected")
                .description("Fraud attempts rejected, grouped by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** Wraps a redemption call so the latency series captures real end-to-end. */
    public Timer redemptionLatency() {
        return redemptionLatency;
    }

    public void addPointsEarned(BigDecimal amount) {
        if (amount != null && amount.signum() > 0) pointsEarned.increment(amount.doubleValue());
    }

    public void addPointsRedeemed(BigDecimal amount) {
        if (amount != null && amount.signum() > 0) pointsRedeemed.increment(amount.doubleValue());
    }

    public void addPointsExpired(BigDecimal amount) {
        if (amount != null && amount.signum() > 0) pointsExpired.increment(amount.doubleValue());
    }

    /** Records that {@code count} wallets were found drifting in a reconciliation run. */
    public void incReconciliationDrift(long count) {
        if (count > 0) reconciliationDrift.increment(count);
    }

    /** Records that {@code count} drifting wallets were rebuilt from the ledger. */
    public void incReconciliationRepaired(long count) {
        if (count > 0) reconciliationRepaired.increment(count);
    }

    /**
     * Counter for shop checkouts (the payment-service entrypoint that wires
     * loyalty into a real POS payment). Tags so dashboards can split by:
     *   - outcome={success, rejected}
     *   - mode={cash, points, mixed}
     * Rejected checkouts also call {@link #incShopCheckoutRejected(String)} so
     * the failure reason gets its own series.
     */
    public void incShopCheckout(String outcome, String mode) {
        Counter.builder("loyalty.shop_checkout")
                .description("Shop checkouts handled by loyalty-service, by outcome and payment mode")
                .tag("outcome", outcome)
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    public void incShopCheckoutRejected(String reason) {
        Counter.builder("loyalty.shop_checkout.rejected")
                .description("Shop checkouts rejected by loyalty-service, grouped by reason code")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}

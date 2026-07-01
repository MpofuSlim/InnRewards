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

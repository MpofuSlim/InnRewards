package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Balances;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Money;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Stuck;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.StuckItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Checks the partner voucher money every day (V70): the money that should be in
 * holding must be accounted for by the vouchers and uses behind it.
 *
 * <pre>
 *   expected in holding = paid in (Flow 1 sales confirmed into holding)
 *                       - settled out (uses paid from holding to the redeeming merchant)
 *   accounted for       = unspent balance of those vouchers (expired ones included, until D1 releases them)
 *                       + uses not settled yet (owed to merchants)
 *                       + sales paid whose voucher is not issued yet
 *   ledger difference   = expected - accounted, which must be 0
 * </pre>
 *
 * A non-zero ledger difference means our own records disagree with each other,
 * which no outside event can cause: it is a bug, and is paged. When the funding
 * provider reports the holding account's balance, the bank difference
 * (bank - expected) is checked as well. Nothing reports one until the real
 * provider is connected, so the bank columns stay empty on staging.
 *
 * <p>All figures are read in ONE read-only, repeatable-read transaction, so a
 * sale or use committing while the run reads cannot make a false difference.
 * The provider is asked after that transaction closes (never a remote call with
 * a connection held), and each run is stored, one row per currency, as an
 * append-only record. Stuck items (a payment with no answer, a use not settled,
 * a funded sale with no voucher) are counted on the row and listed live on the
 * admin view; they are warnings, not part of the arithmetic.
 */
@Service
public class PartnerHoldingReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PartnerHoldingReconciliation.class);
    static final int SCALE = 4;
    /** Most stuck items listed on the admin view. */
    public static final int STUCK_ITEMS_LIMIT = 50;

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Status { BALANCED, LEDGER_MISMATCH, BANK_MISMATCH }

    /** One currency's line of a run. */
    public record Line(String currency, BigDecimal paidIn, BigDecimal settledOut, BigDecimal expectedHolding,
                       BigDecimal outstandingBalance, BigDecimal expiredBalance, BigDecimal owedToMerchants,
                       BigDecimal paidNotIssued, BigDecimal ledgerDifference, BigDecimal fundingInFlight,
                       BigDecimal bankBalance, BigDecimal bankDifference, int stuckFunding, int stuckSettlements,
                       int stuckPayments, int paidWithoutVoucher, Status status) {}

    /** One run: every currency checked at one moment. */
    public record Run(UUID runId, Instant runAt, Trigger trigger, String triggeredBy, Status status,
                      List<Line> currencies) {}

    private final PartnerLedger ledger;
    private final VoucherFundingProvider provider;
    private final VoucherPartnerProperties props;
    private final LoyaltyMetrics metrics;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate snapshotTx;
    private final TransactionTemplate writeTx;
    private final Duration stuckAfter;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PartnerHoldingReconciliation(PartnerLedger ledger, VoucherFundingProvider provider,
                                        VoucherPartnerProperties props, LoyaltyMetrics metrics,
                                        DataSource dataSource, PlatformTransactionManager transactionManager,
                                        @Value("${loyalty.voucher-partner.reconciliation.stuck-after:PT1H}")
                                        Duration stuckAfter) {
        this(ledger, provider, props, metrics, dataSource, transactionManager, stuckAfter, Clock.systemUTC());
    }

    PartnerHoldingReconciliation(PartnerLedger ledger, VoucherFundingProvider provider,
                                 VoucherPartnerProperties props, LoyaltyMetrics metrics, DataSource dataSource,
                                 PlatformTransactionManager transactionManager, Duration stuckAfter, Clock clock) {
        this.ledger = ledger;
        this.provider = provider;
        this.props = props;
        this.metrics = metrics;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.stuckAfter = stuckAfter == null || stuckAfter.isNegative() || stuckAfter.isZero()
                ? Duration.ofHours(1) : stuckAfter;
        this.clock = clock;
    }

    /** Everything read for one run, from one snapshot. */
    record Snapshot(Map<String, Money> paidIn, Map<String, Money> settledOut, Map<String, Money> owed,
                            Map<String, Balances> balances, Map<String, Money> paidNotIssued,
                            Map<String, Money> inFlight, Map<String, Stuck> stuck) {}

    /** Runs the reconciliation now, stores it, updates the metrics and returns it. */
    public Run run(Trigger trigger, String triggeredBy) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Snapshot s;
        try {
            s = snapshotTx.execute(tx -> new Snapshot(ledger.paidIn(), ledger.settledOut(), ledger.owed(),
                    ledger.balances(now), ledger.paidNotIssued(), ledger.fundingInFlight(),
                    ledger.stuck(now.minus(stuckAfter))));
        } catch (RuntimeException e) {
            metrics.incPartnerReconciliation("failed");
            throw e;
        }
        if (s == null) {
            metrics.incPartnerReconciliation("failed");
            throw new IllegalStateException("The partner holding snapshot returned nothing");
        }

        List<Line> lines = new ArrayList<>();
        for (String currency : currencies(s)) {
            lines.add(line(currency, s, bankBalance(currency)));
        }
        Status overall = worst(lines);
        Run run = new Run(UUID.randomUUID(), now, trigger, triggeredBy, overall, List.copyOf(lines));
        try {
            writeTx.executeWithoutResult(tx -> store(run));
        } catch (RuntimeException e) {
            metrics.incPartnerReconciliation("failed");
            throw e;
        }
        report(run);
        return run;
    }

    /** The most recent runs, newest first, at most {@code limit} runs. */
    public List<Run> recent(int limit) {
        int n = Math.clamp(limit, 1, 100);
        List<UUID> runIds = jdbc.queryForList("""
                SELECT run_id FROM partner_reconciliation_runs
                GROUP BY run_id ORDER BY MAX(run_at) DESC, run_id LIMIT :n""",
                new MapSqlParameterSource("n", n), UUID.class);
        if (runIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Run> byId = new LinkedHashMap<>();
        runIds.forEach(id -> byId.put(id, null));
        Map<UUID, List<Line>> lines = new LinkedHashMap<>();
        jdbc.query("""
                SELECT * FROM partner_reconciliation_runs WHERE run_id IN (:ids)
                ORDER BY run_at DESC, currency""", new MapSqlParameterSource("ids", runIds), rs -> {
            UUID runId = rs.getObject("run_id", UUID.class);
            lines.computeIfAbsent(runId, k -> new ArrayList<>()).add(readLine(rs));
            if (byId.get(runId) == null) {
                byId.put(runId, new Run(runId, rs.getObject("run_at", OffsetDateTime.class).toInstant(),
                        Trigger.valueOf(rs.getString("trigger")), rs.getString("triggered_by"), null, List.of()));
            }
        });
        List<Run> out = new ArrayList<>();
        for (Map.Entry<UUID, Run> e : byId.entrySet()) {
            Run head = e.getValue();
            if (head == null) {
                continue;
            }
            List<Line> ls = List.copyOf(lines.getOrDefault(e.getKey(), List.of()));
            out.add(new Run(head.runId(), head.runAt(), head.trigger(), head.triggeredBy(), worst(ls), ls));
        }
        return out;
    }

    /** What is stuck right now, oldest first: read live, not from a run. */
    public List<StuckItem> stuckNow() {
        return ledger.stuckItems(clock.instant().minus(stuckAfter), STUCK_ITEMS_LIMIT);
    }

    public Duration stuckAfter() {
        return stuckAfter;
    }

    // ---------------------------------------------------------------- helpers

    /** The configured currencies first, then any other currency a record holds. */
    private Set<String> currencies(Snapshot s) {
        Set<String> out = new LinkedHashSet<>(new TreeSet<>(props.currencySet()));
        Set<String> seen = new TreeSet<>();
        seen.addAll(s.paidIn().keySet());
        seen.addAll(s.settledOut().keySet());
        seen.addAll(s.owed().keySet());
        seen.addAll(s.balances().keySet());
        seen.addAll(s.paidNotIssued().keySet());
        seen.addAll(s.inFlight().keySet());
        seen.addAll(s.stuck().keySet());
        out.addAll(seen);
        return out;
    }

    private Optional<BigDecimal> bankBalance(String currency) {
        try {
            return provider.holdingBalance(currency).map(PartnerHoldingReconciliation::scaled);
        } catch (RuntimeException e) {
            // The provider not answering is not a difference: the run still checks
            // our own records, and the bank columns stay empty for this run.
            log.warn("Partner holding balance unavailable currency={}", currency, e);
            return Optional.empty();
        }
    }

    static Line line(String currency, Snapshot s, Optional<BigDecimal> bank) {
        BigDecimal paidIn = amount(s.paidIn(), currency);
        BigDecimal settledOut = amount(s.settledOut(), currency);
        Balances b = s.balances().getOrDefault(currency, Balances.ZERO);
        BigDecimal outstanding = scaled(b.outstanding().amount());
        BigDecimal expired = scaled(b.expired().amount());
        BigDecimal owed = amount(s.owed(), currency);
        BigDecimal paidNotIssued = amount(s.paidNotIssued(), currency);
        BigDecimal expected = paidIn.subtract(settledOut);
        BigDecimal accounted = outstanding.add(expired).add(owed).add(paidNotIssued);
        BigDecimal ledgerDifference = expected.subtract(accounted);
        BigDecimal bankBalance = bank.orElse(null);
        BigDecimal bankDifference = bankBalance == null ? null : bankBalance.subtract(expected);
        Stuck st = s.stuck().getOrDefault(currency, Stuck.NONE);
        Status status = ledgerDifference.signum() != 0 ? Status.LEDGER_MISMATCH
                : bankDifference != null && bankDifference.signum() != 0 ? Status.BANK_MISMATCH
                : Status.BALANCED;
        return new Line(currency, paidIn, settledOut, expected, outstanding, expired, owed, paidNotIssued,
                ledgerDifference, amount(s.inFlight(), currency), bankBalance, bankDifference,
                st.funding(), st.settlements(), st.payments(), st.paidWithoutVoucher(), status);
    }

    static Status worst(List<Line> lines) {
        Status worst = Status.BALANCED;
        for (Line l : lines) {
            if (l.status() == Status.LEDGER_MISMATCH) {
                return Status.LEDGER_MISMATCH;
            }
            if (l.status() == Status.BANK_MISMATCH) {
                worst = Status.BANK_MISMATCH;
            }
        }
        return worst;
    }

    private void store(Run run) {
        for (Line l : run.currencies()) {
            jdbc.update("""
                    INSERT INTO partner_reconciliation_runs (id, run_id, run_at, trigger, triggered_by, currency,
                        paid_in, settled_out, expected_holding, outstanding_balance, expired_balance,
                        owed_to_merchants, paid_not_issued, ledger_difference, funding_in_flight,
                        bank_balance, bank_difference, stuck_funding, stuck_settlements, stuck_payments,
                        paid_without_voucher, status)
                    VALUES (:id, :runId, :runAt, :trigger, :triggeredBy, :currency,
                        :paidIn, :settledOut, :expected, :outstanding, :expired,
                        :owed, :paidNotIssued, :ledgerDifference, :inFlight,
                        :bankBalance, :bankDifference, :stuckFunding, :stuckSettlements, :stuckPayments,
                        :paidWithoutVoucher, :status)""",
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("runId", run.runId())
                            .addValue("runAt", PartnerLedger.at(run.runAt()))
                            .addValue("trigger", run.trigger().name())
                            .addValue("triggeredBy", run.triggeredBy())
                            .addValue("currency", l.currency())
                            .addValue("paidIn", l.paidIn())
                            .addValue("settledOut", l.settledOut())
                            .addValue("expected", l.expectedHolding())
                            .addValue("outstanding", l.outstandingBalance())
                            .addValue("expired", l.expiredBalance())
                            .addValue("owed", l.owedToMerchants())
                            .addValue("paidNotIssued", l.paidNotIssued())
                            .addValue("ledgerDifference", l.ledgerDifference())
                            .addValue("inFlight", l.fundingInFlight())
                            .addValue("bankBalance", l.bankBalance())
                            .addValue("bankDifference", l.bankDifference())
                            .addValue("stuckFunding", l.stuckFunding())
                            .addValue("stuckSettlements", l.stuckSettlements())
                            .addValue("stuckPayments", l.stuckPayments())
                            .addValue("paidWithoutVoucher", l.paidWithoutVoucher())
                            .addValue("status", l.status().name()));
        }
    }

    private static Line readLine(ResultSet rs) throws SQLException {
        return new Line(rs.getString("currency"), rs.getBigDecimal("paid_in"), rs.getBigDecimal("settled_out"),
                rs.getBigDecimal("expected_holding"), rs.getBigDecimal("outstanding_balance"),
                rs.getBigDecimal("expired_balance"), rs.getBigDecimal("owed_to_merchants"),
                rs.getBigDecimal("paid_not_issued"), rs.getBigDecimal("ledger_difference"),
                rs.getBigDecimal("funding_in_flight"), rs.getBigDecimal("bank_balance"),
                rs.getBigDecimal("bank_difference"), rs.getInt("stuck_funding"), rs.getInt("stuck_settlements"),
                rs.getInt("stuck_payments"), rs.getInt("paid_without_voucher"),
                Status.valueOf(rs.getString("status")));
    }

    /** Metrics and one log line. Never fails the run: the row is already stored. */
    private void report(Run run) {
        try {
            metrics.incPartnerReconciliation(run.status().name().toLowerCase(java.util.Locale.ROOT));
            for (Line l : run.currencies()) {
                metrics.setPartnerHoldingGauge("loyalty.partner.holding.ledger_difference",
                        "Partner holding: money expected in holding minus what the vouchers and uses account for "
                                + "(must be 0)", l.ledgerDifference().doubleValue(), "currency", l.currency());
                metrics.setPartnerHoldingGauge("loyalty.partner.holding.expected",
                        "Partner holding: money that should be in the holding account",
                        l.expectedHolding().doubleValue(), "currency", l.currency());
                if (l.bankDifference() != null) {
                    metrics.setPartnerHoldingGauge("loyalty.partner.holding.bank_difference",
                            "Partner holding: the provider's holding balance minus what should be there",
                            l.bankDifference().doubleValue(), "currency", l.currency());
                }
                stuckGauge(l, "funding", l.stuckFunding());
                stuckGauge(l, "settlement", l.stuckSettlements());
                stuckGauge(l, "payment", l.stuckPayments());
                stuckGauge(l, "paid_without_voucher", l.paidWithoutVoucher());
            }
            metrics.setPartnerHoldingGauge("loyalty.partner.reconciliation.last_run_seconds",
                    "When the partner holding reconciliation last completed (epoch seconds)",
                    run.runAt().getEpochSecond());
        } catch (RuntimeException e) {
            log.warn("Partner holding metrics not updated runId={}", run.runId(), e);
        }
        for (Line l : run.currencies()) {
            String msg = "Partner holding reconciliation runId={} currency={} status={} expected={} "
                    + "ledgerDifference={} bankDifference={} owed={} stuck={}";
            Object[] args = {run.runId(), l.currency(), l.status(), l.expectedHolding(), l.ledgerDifference(),
                    l.bankDifference(), l.owedToMerchants(),
                    l.stuckFunding() + l.stuckSettlements() + l.stuckPayments() + l.paidWithoutVoucher()};
            if (l.status() == Status.BALANCED) {
                log.info(msg, args);
            } else {
                log.error(msg, args);
            }
        }
    }

    private void stuckGauge(Line l, String kind, int value) {
        metrics.setPartnerHoldingGauge("loyalty.partner.holding.stuck",
                "Partner money items that should have moved on by now, by kind", value,
                "currency", l.currency(), "kind", kind);
    }

    private static BigDecimal amount(Map<String, Money> m, String currency) {
        Money money = m.get(currency);
        return scaled(money == null ? BigDecimal.ZERO : money.amount());
    }

    private static BigDecimal scaled(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }
}

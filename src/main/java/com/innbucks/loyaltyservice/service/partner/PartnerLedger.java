package com.innbucks.loyaltyservice.service.partner;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The partner voucher money, read straight from the records the partner flow
 * already keeps: orders (what was paid in), vouchers (what is unspent),
 * redemptions (what was used and whether it was settled) and Flow 2 payments.
 * Nothing here writes, and nothing keeps a running total of its own, so a
 * figure can never drift from the rows it sums.
 *
 * <p><b>Holding</b> only ever sees Flow 1: a sale confirmed into holding, then
 * each use paid from holding to the merchant where it was redeemed. A Flow 2
 * code (V69) is paid by the agent straight to its issuer, so its order, its
 * voucher and its one use are left out of every holding figure. A voucher is
 * "held" when it is a partner voucher and no pay-at-redeem order issued it.
 *
 * <p>Amounts are summed per currency; nothing is ever added across currencies.
 * The fragments below are spliced into the queries by {@link #sql}; each is
 * bracketed, so it can sit next to any other condition.
 */
@Component
public class PartnerLedger {

    /** A partner voucher whose value went through holding (not a Flow 2 code). */
    static final String HELD_VOUCHER = """
            (v.partner_id IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM voucher_purchase_orders po
                             WHERE po.voucher_id = v.id AND po.pay_at_redeem))""";

    /** A Flow 1 partner sale whose money was confirmed into holding. */
    static final String PAID_IN_ORDER = """
            (o.partner_id IS NOT NULL AND o.pay_at_redeem = FALSE AND o.funding_status = 'CONFIRMED')""";

    /** A use not yet paid out of holding. A missing status counts as owed: it was never paid. */
    static final String NOT_SETTLED = "(COALESCE(r.settlement_status, 'PENDING') <> 'SETTLED')";

    /**
     * A partner sale whose payment should have resolved before {@code :before}: sent
     * with no answer, or an InnBucks code past its expiry that nothing has closed.
     */
    static final String STUCK_FUNDING = """
            (o.partner_id IS NOT NULL
             AND ((o.funding_status = 'PENDING' AND o.funding_started_at < :before)
               OR (o.funding_status = 'AWAITING' AND o.payment_expires_at < :before)))""";

    /** A Flow 2 payment with no answer, or confirmed with no voucher recorded, since before {@code :before}. */
    static final String STUCK_PAYMENT = """
            ((p.status = 'PENDING' AND p.started_at < :before)
             OR (p.status = 'CONFIRMED' AND p.redemption_id IS NULL
                 AND COALESCE(p.finished_at, p.started_at) < :before))""";

    private final NamedParameterJdbcTemplate jdbc;

    public PartnerLedger(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** An amount and how many rows made it. */
    public record Money(BigDecimal amount, long count) {
        public static final Money ZERO = new Money(BigDecimal.ZERO, 0);
    }

    /** Unspent value of held vouchers: still usable, and past expiry (awaiting release, D1). */
    public record Balances(Money outstanding, Money expired) {
        public static final Balances ZERO = new Balances(Money.ZERO, Money.ZERO);
    }

    /** Items that should have moved on by now: each a warning, never a figure. */
    public record Stuck(int funding, int settlements, int payments, int paidWithoutVoucher) {
        public static final Stuck NONE = new Stuck(0, 0, 0, 0);
    }

    // ============================================================ reconciliation

    /** Flow 1 sales confirmed into holding, ever. */
    public Map<String, Money> paidIn() {
        return moneyByCurrency(sql("""
                SELECT o.currency AS currency, COALESCE(SUM(o.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_purchase_orders o
                WHERE {PAID_IN}
                GROUP BY o.currency"""), new MapSqlParameterSource());
    }

    /** Uses of held vouchers paid out of holding, ever. */
    public Map<String, Money> settledOut() {
        return moneyByCurrency(sql("""
                SELECT v.currency AS currency, COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.result = 'SUCCESS' AND r.settlement_status = 'SETTLED' AND {HELD}
                GROUP BY v.currency"""), new MapSqlParameterSource());
    }

    /** Uses of held vouchers not yet paid out of holding: owed to the merchants that redeemed them. */
    public Map<String, Money> owed() {
        return moneyByCurrency(sql("""
                SELECT v.currency AS currency, COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD}
                GROUP BY v.currency"""), new MapSqlParameterSource());
    }

    /** Unspent value of every held voucher, split at {@code now} into usable and expired. */
    public Map<String, Balances> balances(Instant now) {
        return balances(sql("""
                SELECT v.currency AS currency,
                       COALESCE(SUM(v.remaining_balance) FILTER (WHERE v.expires_at IS NULL OR v.expires_at > :now), 0) AS live,
                       COUNT(*) FILTER (WHERE v.expires_at IS NULL OR v.expires_at > :now) AS live_n,
                       COALESCE(SUM(v.remaining_balance) FILTER (WHERE v.expires_at <= :now), 0) AS expired,
                       COUNT(*) FILTER (WHERE v.expires_at <= :now) AS expired_n
                FROM vouchers v
                WHERE COALESCE(v.remaining_balance, 0) > 0 AND {HELD}
                GROUP BY v.currency"""), new MapSqlParameterSource("now", at(now)));
    }

    /** Flow 1 sales confirmed into holding whose voucher has not been issued yet. */
    public Map<String, Money> paidNotIssued() {
        return moneyByCurrency(sql("""
                SELECT o.currency AS currency, COALESCE(SUM(o.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_purchase_orders o
                WHERE {PAID_IN} AND o.voucher_id IS NULL
                GROUP BY o.currency"""), new MapSqlParameterSource());
    }

    /** Flow 1 sales whose money is on its way to holding: sent, outcome not known yet. */
    public Map<String, Money> fundingInFlight() {
        return moneyByCurrency("""
                SELECT o.currency AS currency, COALESCE(SUM(o.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_purchase_orders o
                WHERE o.partner_id IS NOT NULL AND o.pay_at_redeem = FALSE AND o.funding_status = 'PENDING'
                GROUP BY o.currency""", new MapSqlParameterSource());
    }

    /** Per currency, what has sat longer than it should: started before {@code before}. */
    public Map<String, Stuck> stuck(Instant before) {
        MapSqlParameterSource p = new MapSqlParameterSource("before", at(before));
        Map<String, Integer> funding = countsByCurrency(sql("""
                SELECT o.currency AS currency, COUNT(*) AS n FROM voucher_purchase_orders o
                WHERE {STUCK_FUNDING}
                GROUP BY o.currency"""), p);
        Map<String, Integer> settlements = countsByCurrency(sql("""
                SELECT v.currency AS currency, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD} AND r.redeemed_at < :before
                GROUP BY v.currency"""), p);
        Map<String, Integer> payments = countsByCurrency(sql("""
                SELECT p.currency AS currency, COUNT(*) AS n FROM voucher_redeem_payments p
                WHERE {STUCK_PAYMENT}
                GROUP BY p.currency"""), p);
        Map<String, Integer> withoutVoucher = countsByCurrency(sql("""
                SELECT o.currency AS currency, COUNT(*) AS n FROM voucher_purchase_orders o
                WHERE {PAID_IN} AND o.voucher_id IS NULL AND COALESCE(o.paid_at, o.funding_started_at) < :before
                GROUP BY o.currency"""), p);
        Map<String, Stuck> out = new LinkedHashMap<>();
        for (String c : union(funding.keySet(), settlements.keySet(), payments.keySet(), withoutVoucher.keySet())) {
            out.put(c, new Stuck(funding.getOrDefault(c, 0), settlements.getOrDefault(c, 0),
                    payments.getOrDefault(c, 0), withoutVoucher.getOrDefault(c, 0)));
        }
        return out;
    }

    /** What a stuck item is. */
    public enum StuckKind { FUNDING, SETTLEMENT, PAYMENT, PAID_WITHOUT_VOUCHER }

    /** One item that should have moved on by now, for an operator to look at. */
    public record StuckItem(StuckKind kind, UUID id, UUID merchantId, String reference, BigDecimal amount,
                            String currency, Instant since) {}

    /** The stuck items, oldest first, at most {@code limit}: the same tests {@link #stuck} counts. */
    public List<StuckItem> stuckItems(Instant before, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("before", at(before)).addValue("limit", limit);
        return jdbc.query(sql("""
                SELECT * FROM (
                  SELECT 'FUNDING' AS kind, o.id AS id, o.merchant_id AS merchant_id,
                         o.partner_reference AS reference, o.amount AS amount, o.currency AS currency,
                         COALESCE(o.funding_started_at, o.payment_expires_at) AS since
                  FROM voucher_purchase_orders o
                  WHERE {STUCK_FUNDING}
                  UNION ALL
                  SELECT 'SETTLEMENT', r.id, r.merchant_id, r.reference, r.amount, v.currency, r.redeemed_at
                  FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                  WHERE r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD} AND r.redeemed_at < :before
                  UNION ALL
                  SELECT 'PAYMENT', p.id, p.merchant_id, p.reference, p.amount, p.currency, p.started_at
                  FROM voucher_redeem_payments p
                  WHERE {STUCK_PAYMENT}
                  UNION ALL
                  SELECT 'PAID_WITHOUT_VOUCHER', o.id, o.merchant_id, o.partner_reference, o.amount, o.currency,
                         COALESCE(o.paid_at, o.funding_started_at)
                  FROM voucher_purchase_orders o
                  WHERE {PAID_IN} AND o.voucher_id IS NULL AND COALESCE(o.paid_at, o.funding_started_at) < :before
                ) s
                ORDER BY s.since, s.id
                LIMIT :limit"""), p, (rs, i) -> new StuckItem(StuckKind.valueOf(rs.getString("kind")),
                rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getString("reference"), rs.getBigDecimal("amount"), rs.getString("currency"),
                instant(rs, "since")));
    }

    // ================================================================ statements

    /** One merchant and one currency, grouped: e.g. uses at Bola Bet of MWOS's vouchers. */
    public record MerchantMoney(String currency, UUID merchantId, String merchantName, Money money) {}

    /** Partner vouchers used at {@code merchantId} in [from, to), by currency and issuer. Both flows. */
    public List<MerchantMoney> redeemedAtByIssuer(UUID merchantId, Instant from, Instant to) {
        return merchantMoney("""
                SELECT v.currency AS currency, v.merchant_id AS merchant_id, im.name AS merchant_name,
                       COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r
                JOIN vouchers v ON v.id = r.voucher_id
                LEFT JOIN merchants im ON im.id = v.merchant_id
                WHERE r.merchant_id = :m AND v.partner_id IS NOT NULL AND r.result = 'SUCCESS'
                  AND r.redeemed_at >= :from AND r.redeemed_at < :to
                GROUP BY v.currency, v.merchant_id, im.name
                ORDER BY v.currency, amount DESC, im.name""", period(merchantId, from, to));
    }

    /** Uses at {@code merchantId} paid to it out of holding in [from, to). */
    public Map<String, Money> settledTo(UUID merchantId, Instant from, Instant to) {
        return moneyByCurrency(sql("""
                SELECT v.currency AS currency, COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.merchant_id = :m AND r.result = 'SUCCESS' AND r.settlement_status = 'SETTLED'
                  AND r.settled_at >= :from AND r.settled_at < :to AND {HELD}
                GROUP BY v.currency"""), period(merchantId, from, to));
    }

    /** Uses at {@code merchantId} not yet paid to it out of holding, ever. */
    public Map<String, Money> owedTo(UUID merchantId) {
        return moneyByCurrency(sql("""
                SELECT v.currency AS currency, COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.merchant_id = :m AND r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD}
                GROUP BY v.currency"""), new MapSqlParameterSource("m", merchantId));
    }

    /** One use owed to a merchant: what its own records will show. */
    public record OwedItem(UUID redemptionId, Instant redeemedAt, String reference, UUID issuerMerchantId,
                           String issuerName, BigDecimal amount, String currency, String settlementStatus) {}

    /** The uses owed to {@code merchantId}, oldest first, at most {@code limit}. */
    public List<OwedItem> owedItems(UUID merchantId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("m", merchantId).addValue("limit", limit);
        return jdbc.query(sql("""
                SELECT r.id AS id, r.redeemed_at AS redeemed_at, r.reference AS reference,
                       v.merchant_id AS issuer_id, im.name AS issuer_name, r.amount AS amount,
                       v.currency AS currency, COALESCE(r.settlement_status, 'PENDING') AS settlement_status
                FROM voucher_redemptions r
                JOIN vouchers v ON v.id = r.voucher_id
                LEFT JOIN merchants im ON im.id = v.merchant_id
                WHERE r.merchant_id = :m AND r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD}
                ORDER BY r.redeemed_at, r.id
                LIMIT :limit"""), p, (rs, i) -> new OwedItem(
                rs.getObject("id", UUID.class), instant(rs, "redeemed_at"), rs.getString("reference"),
                rs.getObject("issuer_id", UUID.class), rs.getString("issuer_name"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("settlement_status")));
    }

    /** Flow 2 payments made by agents straight to {@code merchantId}, confirmed in [from, to). */
    public Map<String, Money> paidAtRedeemTo(UUID merchantId, Instant from, Instant to) {
        return moneyByCurrency("""
                SELECT p.currency AS currency, COALESCE(SUM(p.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redeem_payments p
                WHERE p.merchant_id = :m AND p.status = 'CONFIRMED'
                  AND p.finished_at >= :from AND p.finished_at < :to
                GROUP BY p.currency""", period(merchantId, from, to));
    }

    /** One currency and one payment method. */
    public record MethodMoney(String currency, String method, Money money) {}

    /** Flow 1 sales of {@code merchantId} confirmed into holding in [from, to), by method. */
    public List<MethodMoney> soldBy(UUID merchantId, Instant from, Instant to) {
        return jdbc.query(sql("""
                SELECT o.currency AS currency, o.funding_method AS method,
                       COALESCE(SUM(o.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_purchase_orders o
                WHERE o.merchant_id = :m AND {PAID_IN}
                  AND COALESCE(o.paid_at, o.funding_started_at) >= :from
                  AND COALESCE(o.paid_at, o.funding_started_at) < :to
                GROUP BY o.currency, o.funding_method
                ORDER BY o.currency, o.funding_method"""), period(merchantId, from, to),
                (rs, i) -> new MethodMoney(rs.getString("currency"), rs.getString("method"),
                        new Money(rs.getBigDecimal("amount"), rs.getLong("n"))));
    }

    /** Uses in [from, to) of the held vouchers {@code issuerId} sold, by the merchant where each was used. */
    public List<MerchantMoney> issuedRedeemedAt(UUID issuerId, Instant from, Instant to) {
        return merchantMoney(sql("""
                SELECT v.currency AS currency, r.merchant_id AS merchant_id, rm.name AS merchant_name,
                       COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r
                JOIN vouchers v ON v.id = r.voucher_id
                LEFT JOIN merchants rm ON rm.id = r.merchant_id
                WHERE v.merchant_id = :m AND r.result = 'SUCCESS'
                  AND r.redeemed_at >= :from AND r.redeemed_at < :to AND {HELD}
                GROUP BY v.currency, r.merchant_id, rm.name
                ORDER BY v.currency, amount DESC, rm.name"""), period(issuerId, from, to));
    }

    /** Unspent value of the held vouchers {@code issuerId} sold, split at {@code now}. */
    public Map<String, Balances> issuerBalances(UUID issuerId, Instant now) {
        return balances(sql("""
                SELECT v.currency AS currency,
                       COALESCE(SUM(v.remaining_balance) FILTER (WHERE v.expires_at IS NULL OR v.expires_at > :now), 0) AS live,
                       COUNT(*) FILTER (WHERE v.expires_at IS NULL OR v.expires_at > :now) AS live_n,
                       COALESCE(SUM(v.remaining_balance) FILTER (WHERE v.expires_at <= :now), 0) AS expired,
                       COUNT(*) FILTER (WHERE v.expires_at <= :now) AS expired_n
                FROM vouchers v
                WHERE v.merchant_id = :m AND COALESCE(v.remaining_balance, 0) > 0 AND {HELD}
                GROUP BY v.currency"""), new MapSqlParameterSource("m", issuerId).addValue("now", at(now)));
    }

    // ============================================================ platform view

    /** A merchant linked to a partner: the rows of the platform view. */
    public record LinkedMerchant(UUID merchantId, String name, UUID tenantId, String codes) {}

    /** Every merchant linked to a partner, by name. */
    public List<LinkedMerchant> linkedMerchants() {
        return jdbc.query("""
                SELECT m.id AS id, m.name AS name, m.tenant_id AS tenant_id,
                       string_agg(DISTINCT l.merchant_code, ',' ORDER BY l.merchant_code) AS codes
                FROM voucher_partner_merchants l JOIN merchants m ON m.id = l.merchant_id
                GROUP BY m.id, m.name, m.tenant_id
                ORDER BY m.name, m.id""", new MapSqlParameterSource(),
                (rs, i) -> new LinkedMerchant(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("codes")));
    }

    /** A merchant and currency's figure, for the platform view. */
    public record Keyed(UUID merchantId, String currency, Money money) {}

    /** Flow 1 sales confirmed in [from, to), per selling merchant. */
    public List<Keyed> soldPerMerchant(Instant from, Instant to) {
        return keyed(sql("""
                SELECT o.merchant_id AS merchant_id, o.currency AS currency,
                       COALESCE(SUM(o.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_purchase_orders o
                WHERE {PAID_IN}
                  AND COALESCE(o.paid_at, o.funding_started_at) >= :from
                  AND COALESCE(o.paid_at, o.funding_started_at) < :to
                GROUP BY o.merchant_id, o.currency"""), period(null, from, to));
    }

    /** Partner vouchers used in [from, to), per merchant where each was used. Both flows. */
    public List<Keyed> redeemedPerMerchant(Instant from, Instant to) {
        return keyed("""
                SELECT r.merchant_id AS merchant_id, v.currency AS currency,
                       COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE v.partner_id IS NOT NULL AND r.result = 'SUCCESS'
                  AND r.redeemed_at >= :from AND r.redeemed_at < :to
                GROUP BY r.merchant_id, v.currency""", period(null, from, to));
    }

    /** Uses not yet paid out of holding, per merchant owed. */
    public List<Keyed> owedPerMerchant() {
        return keyed(sql("""
                SELECT r.merchant_id AS merchant_id, v.currency AS currency,
                       COALESCE(SUM(r.amount), 0) AS amount, COUNT(*) AS n
                FROM voucher_redemptions r JOIN vouchers v ON v.id = r.voucher_id
                WHERE r.result = 'SUCCESS' AND {NOT_SETTLED} AND {HELD}
                GROUP BY r.merchant_id, v.currency"""), new MapSqlParameterSource());
    }

    /** Unspent value of held vouchers (expired included), per issuing merchant. */
    public List<Keyed> unspentPerIssuer() {
        return keyed(sql("""
                SELECT v.merchant_id AS merchant_id, v.currency AS currency,
                       COALESCE(SUM(v.remaining_balance), 0) AS amount, COUNT(*) AS n
                FROM vouchers v
                WHERE COALESCE(v.remaining_balance, 0) > 0 AND {HELD}
                GROUP BY v.merchant_id, v.currency"""), new MapSqlParameterSource());
    }

    // ================================================================ helpers

    /** Splices the shared fragments into a query. Every fragment is bracketed. */
    static String sql(String template) {
        return template
                .replace("{HELD}", HELD_VOUCHER)
                .replace("{PAID_IN}", PAID_IN_ORDER)
                .replace("{NOT_SETTLED}", NOT_SETTLED)
                .replace("{STUCK_FUNDING}", STUCK_FUNDING)
                .replace("{STUCK_PAYMENT}", STUCK_PAYMENT);
    }

    private Map<String, Money> moneyByCurrency(String sql, MapSqlParameterSource p) {
        Map<String, Money> out = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString("currency"), new Money(rs.getBigDecimal("amount"), rs.getLong("n")));
        });
        return out;
    }

    private Map<String, Balances> balances(String sql, MapSqlParameterSource p) {
        Map<String, Balances> out = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString("currency"), new Balances(
                    new Money(rs.getBigDecimal("live"), rs.getLong("live_n")),
                    new Money(rs.getBigDecimal("expired"), rs.getLong("expired_n"))));
        });
        return out;
    }

    private Map<String, Integer> countsByCurrency(String sql, MapSqlParameterSource p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString("currency"), rs.getInt("n"));
        });
        return out;
    }

    private List<MerchantMoney> merchantMoney(String sql, MapSqlParameterSource p) {
        return jdbc.query(sql, p, (rs, i) -> new MerchantMoney(rs.getString("currency"),
                rs.getObject("merchant_id", UUID.class), rs.getString("merchant_name"),
                new Money(rs.getBigDecimal("amount"), rs.getLong("n"))));
    }

    private List<Keyed> keyed(String sql, MapSqlParameterSource p) {
        return jdbc.query(sql, p, (rs, i) -> new Keyed(rs.getObject("merchant_id", UUID.class),
                rs.getString("currency"), new Money(rs.getBigDecimal("amount"), rs.getLong("n"))));
    }

    private static MapSqlParameterSource period(UUID merchantId, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", at(from)).addValue("to", at(to));
        if (merchantId != null) {
            p.addValue("m", merchantId);
        }
        return p;
    }

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    @SafeVarargs
    private static List<String> union(Collection<String>... sets) {
        List<String> out = new ArrayList<>();
        for (Collection<String> s : sets) {
            for (String c : s) {
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}

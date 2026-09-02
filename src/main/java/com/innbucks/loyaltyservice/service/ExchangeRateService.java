package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The FX conversion (multi-currency design: USD base + ZAR + ZWG) — the ONLY
 * place in the service that converts between currencies. Every money boundary
 * crosses here exactly once: earn converts the transacted amount to the USD
 * base before the points math; redeem converts the USD value of points into
 * the requested currency. One anchor, no cross-currency arbitrage.
 *
 * <p><b>Bank-rate default, tenant override.</b> Rates resolve per tenant with
 * the precedence in {@link ExchangeRateRepository#currentRate}: a rate the
 * tenant set beats the platform default ("the bank rate" — SUPER_ADMIN-entered
 * today, feed-fed in a later phase), and a platform-admin rate beats the
 * automated feed. The bank rate applies only when nobody set one.
 *
 * <p>Rates are effective-dated and append-only (see {@link ExchangeRate}) —
 * the same resolution model as {@link RedemptionRateService}. There is
 * deliberately NO seed and NO fallback: a supported non-base currency with no
 * in-force rate FAILS CLOSED ({@code NO_FX_RATE}); a missing rate must refuse,
 * never silently price at 1.0.
 *
 * <p><b>Freeze-on-write is the caller's contract:</b> a caller that prices a
 * money row with a conversion from here must stamp the converted value (and the
 * {@link ExchangeRate#getId() rate row id}) onto that row and never recompute it
 * later — the same "read back, never recompute" rule the redemption
 * {@code amount} follows. Revaluing history at today's ZWG rate would rewrite
 * what the platform owed.
 */
@Service
public class ExchangeRateService {

    /** Money scale, matching the NUMERIC(19,4) money columns everywhere else. */
    private static final int MONEY_SCALE = 4;

    private final ExchangeRateRepository rates;
    private final SupportedCurrencies currencies;

    /**
     * Sanity band on {@link #setRate}: a new rate deviating more than this many
     * percent from the rate currently in force for the same scope is refused
     * unless explicitly forced WITH a note. A fat-fingered ZWG rate (one extra
     * zero) would misprice every earn/redeem until noticed; the band makes that
     * a deliberate two-step, and the append-only history makes it attributable.
     * Non-positive disables the band (same convention as the other ceilings).
     */
    private final BigDecimal maxChangePercent;

    public ExchangeRateService(ExchangeRateRepository rates,
                               SupportedCurrencies currencies,
                               @Value("${loyalty.fx.max-change-percent:25}") BigDecimal maxChangePercent) {
        this.rates = rates;
        this.currencies = currencies;
        this.maxChangePercent = maxChangePercent;
    }

    /**
     * The FX rate in force for {@code currency} right now, resolved for
     * {@code tenantId} (null = platform scope only): tenant override → platform
     * ADMIN → platform FEED. Never the base.
     */
    public ExchangeRate currentRate(UUID tenantId, String currency) {
        return currentRate(tenantId, currency, Instant.now());
    }

    public ExchangeRate currentRate(UUID tenantId, String currency, Instant at) {
        String ccy = currencies.requireSupported(currency);
        requireNotBase(ccy);
        return rates.currentRate(tenantId, ccy, at).orElseThrow(() -> LoyaltyException.badRequest(
                "NO_FX_RATE",
                "No exchange rate is configured for " + ccy + ". A platform administrator must set "
                        + "USD→" + ccy + " before this currency can be priced."));
    }

    /**
     * A completed conversion: the converted amount together with the id of the
     * {@link ExchangeRate} row that produced it, so the caller can FREEZE both
     * onto the money row it is writing. {@code rateId} is null for a base-
     * currency conversion (identity — no rate row exists or is needed).
     *
     * <p>Callers that persist money must use {@link #toBaseWithRate} rather than
     * {@link #toBase}: storing the converted value without the rate that made it
     * leaves an unauditable number, and recomputing it later at a newer rate
     * would restate history.
     */
    public record Conversion(BigDecimal amount, UUID rateId) {}

    /**
     * Convert an amount in {@code currency} to its {@link SupportedCurrencies#BASE}
     * (USD) value at the rate in force for {@code tenantId} — the direction earn
     * takes before the points math. Scale {@value #MONEY_SCALE}, HALF_UP. Base
     * in = identity (rescaled), no table read.
     *
     * <p>Use {@link #toBaseWithRate} when the result is going to be persisted.
     */
    public BigDecimal toBase(UUID tenantId, BigDecimal amount, String currency) {
        return toBaseWithRate(tenantId, amount, currency).amount();
    }

    /**
     * {@link #toBase}, but also returning WHICH rate row did the conversion —
     * the form every persisting caller should use, so the stored value and its
     * justification are written together.
     */
    public Conversion toBaseWithRate(UUID tenantId, BigDecimal amount, String currency) {
        requireNonNegative(amount);
        String ccy = currencies.requireSupported(currency);
        if (SupportedCurrencies.BASE.equals(ccy)) {
            return new Conversion(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), null);
        }
        ExchangeRate rate = currentRate(tenantId, ccy);
        return new Conversion(
                amount.divide(rate.getRatePerUsd(), MONEY_SCALE, RoundingMode.HALF_UP),
                rate.getId());
    }

    /**
     * Convert a {@link SupportedCurrencies#BASE} (USD) amount into
     * {@code currency} at the rate in force for {@code tenantId} — the direction
     * redeem takes when a till asks for a discount in local currency. Scale
     * {@value #MONEY_SCALE}, HALF_UP. Base out = identity (rescaled).
     */
    public BigDecimal fromBase(UUID tenantId, BigDecimal baseAmount, String currency) {
        requireNonNegative(baseAmount);
        String ccy = currencies.requireSupported(currency);
        if (SupportedCurrencies.BASE.equals(ccy)) {
            return baseAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return baseAmount.multiply(currentRate(tenantId, ccy).getRatePerUsd())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Record a new effective-dated rate. {@code tenantId} null writes the
     * PLATFORM default (the "bank rate" every tenant inherits); a tenant id
     * writes that tenant's OVERRIDE, which beats the platform rows for that
     * tenant from its effective instant. Append-only: never edits an existing
     * row, so history — and every money row already frozen against a prior
     * rate — is untouched.
     *
     * <p>Guards, in order: currency must be supported and must not be the base
     * ({@code FX_BASE_IMMUTABLE} — USD is 1 by definition); the rate must be
     * strictly positive; and a rate deviating more than the sanity band from
     * the rate currently in force for the same scope (for a tenant's FIRST
     * override, that is the bank rate it overrides) is refused
     * ({@code FX_RATE_OUT_OF_BAND}) unless {@code force} is set together with a
     * non-blank {@code note} explaining why ({@code FX_FORCE_NEEDS_NOTE}). The
     * first rate ever seen for a scope has nothing to deviate from and skips
     * the band.
     *
     * @param effectiveFrom when it takes force; null = now (a future instant
     *                      schedules the change)
     */
    @Transactional
    public ExchangeRate setRate(UUID tenantId, String currency, BigDecimal ratePerUsd,
                                Instant effectiveFrom, boolean force, UUID createdBy, String note) {
        String ccy = currencies.requireSupported(currency);
        requireNotBase(ccy);
        if (ratePerUsd == null || ratePerUsd.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_RATE",
                    "ratePerUsd must be greater than zero — a zero or negative FX rate is meaningless.");
        }

        // Sanity band vs the rate in force NOW for the same scope — what the
        // operator (or tenant) is looking at when they type the new figure.
        if (maxChangePercent != null && maxChangePercent.signum() > 0) {
            rates.currentRate(tenantId, ccy, Instant.now()).ifPresent(current -> {
                BigDecimal old = current.getRatePerUsd();
                BigDecimal changePct = ratePerUsd.subtract(old).abs()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(old, 2, RoundingMode.HALF_UP);
                if (changePct.compareTo(maxChangePercent) > 0) {
                    if (!force) {
                        throw LoyaltyException.badRequest("FX_RATE_OUT_OF_BAND",
                                "New " + ccy + " rate " + ratePerUsd.toPlainString() + " deviates "
                                        + changePct.toPlainString() + "% from the current "
                                        + old.toPlainString() + " (band ±" + maxChangePercent.toPlainString()
                                        + "%). If this is deliberate, resubmit with force=true and a note "
                                        + "explaining why.");
                    }
                    if (note == null || note.isBlank()) {
                        throw LoyaltyException.badRequest("FX_FORCE_NEEDS_NOTE",
                                "Forcing a rate through the sanity band requires a note explaining why — "
                                        + "it becomes part of the permanent rate history.");
                    }
                }
            });
        }

        ExchangeRate r = new ExchangeRate();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setCurrency(ccy);
        r.setRatePerUsd(ratePerUsd);
        r.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : Instant.now());
        r.setSource(ExchangeRate.Source.ADMIN);
        r.setCreatedBy(createdBy);
        r.setNote(note);
        return rates.save(r);
    }

    /** Full history across all scopes for a currency, newest-effective first. */
    public List<ExchangeRate> history(String currency) {
        String ccy = currencies.requireSupported(currency);
        requireNotBase(ccy);
        return rates.findByCurrencyOrderByEffectiveFromDescCreatedAtDesc(ccy);
    }

    private static void requireNotBase(String normalizedCurrency) {
        if (SupportedCurrencies.BASE.equals(normalizedCurrency)) {
            throw LoyaltyException.badRequest("FX_BASE_IMMUTABLE",
                    SupportedCurrencies.BASE + " is the base currency — its rate is 1 by definition "
                            + "and is never stored or set.");
        }
    }

    private static void requireNonNegative(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT",
                    "Amount to convert must be zero or positive.");
        }
    }
}

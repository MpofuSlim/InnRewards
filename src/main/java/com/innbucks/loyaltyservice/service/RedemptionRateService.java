package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.RedemptionRate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.RedemptionRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The redemption formula (business-model point 4): the single, platform-owned,
 * SUPER_ADMIN-set conversion between points and currency. Every spend path reads
 * the value of points through here, so a point's worth at redemption is decided
 * by InnBucks — never by a merchant's till.
 *
 * <p>The rate is effective-dated (see {@link RedemptionRate}); this service
 * resolves the one in force and converts in both directions with defined,
 * documented rounding. The V35 seed guarantees a rate always exists, so
 * {@link #currentRate} never has to invent a fallback.
 */
@Service
public class RedemptionRateService {

    /**
     * Scale for a currency amount: 4 dp, matching {@code loyalty_transactions.amount}
     * (NUMERIC(19,4)) so a stamped redemption value round-trips without loss.
     */
    private static final int MONEY_SCALE = 4;

    private final RedemptionRateRepository rates;

    public RedemptionRateService(RedemptionRateRepository rates) {
        this.rates = rates;
    }

    /** The rate in force for {@code currency} right now. */
    public RedemptionRate currentRate(String currency) {
        return currentRate(currency, Instant.now());
    }

    public RedemptionRate currentRate(String currency, Instant at) {
        String ccy = normalize(currency);
        return rates.currentRate(ccy, at).orElseThrow(() -> LoyaltyException.badRequest(
                "NO_REDEMPTION_RATE",
                "No redemption rate is configured for " + ccy + ". A platform administrator "
                        + "must set one before points can be redeemed in this currency."));
    }

    /**
     * The currency value of {@code points} at the current rate — the dollar cost
     * to the platform of honouring this redemption, stamped onto the ledger row
     * so liability is always known in money terms.
     *
     * <p>Rounded HALF_UP to {@link #MONEY_SCALE} dp: this is a recorded valuation,
     * not a charge, so nearest-value is the honest figure.
     */
    public BigDecimal valueOf(BigDecimal points, String currency) {
        if (points == null) return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal ppu = currentRate(currency).getPointsPerUnit();
        return points.divide(ppu, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The whole-points debit needed to cover {@code amount} of currency at the
     * current rate — used when a caller redeems by DOLLAR value and the server
     * decides the points, the correct direction for the model.
     *
     * <p>Rounded HALF_UP to a whole number: points are integral (RulesEngine
     * floors earned points the same way), and HALF_UP keeps the customer-facing
     * cost of a given discount stable rather than biased.
     */
    public BigDecimal pointsFor(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "Redemption amount must be greater than zero.");
        }
        BigDecimal ppu = currentRate(currency).getPointsPerUnit();
        return amount.multiply(ppu).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Set a new platform rate, effective-dated. Append-only: this never edits an
     * existing row, so the prior rate stays as history and any redemption already
     * valued against it is untouched.
     *
     * @param effectiveFrom when it takes force; null means now (a future instant
     *                      schedules the change)
     */
    @Transactional
    public RedemptionRate setRate(BigDecimal pointsPerUnit, String currency,
                                  Instant effectiveFrom, UUID createdBy, String note) {
        if (pointsPerUnit == null || pointsPerUnit.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_RATE",
                    "pointsPerUnit must be greater than zero — a zero or negative rate would make "
                            + "points free or invert their value.");
        }
        RedemptionRate r = new RedemptionRate();
        r.setId(UUID.randomUUID());
        r.setPointsPerUnit(pointsPerUnit);
        r.setCurrency(normalize(currency));
        r.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : Instant.now());
        r.setCreatedBy(createdBy);
        r.setNote(note);
        return rates.save(r);
    }

    public List<RedemptionRate> history(String currency) {
        return rates.findByCurrencyOrderByEffectiveFromDescCreatedAtDesc(normalize(currency));
    }

    private static String normalize(String currency) {
        return (currency == null || currency.isBlank()) ? "USD" : currency.trim().toUpperCase();
    }
}

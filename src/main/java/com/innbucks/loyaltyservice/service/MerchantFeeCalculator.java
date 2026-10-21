package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Compute the merchant's fee for a single voucher under the three-mode
 * model on {@link Merchant.FeeType}.
 *
 * <p>Stateless / pure — no spring annotations on purpose; this is called
 * from invoicing (per-voucher in the period being billed) and reporting
 * (per-voucher in the dashboard window). Wrapping it in a {@code @Service}
 * bean would force callers through the application context and make the
 * unit tests pay the spring startup cost for what is plain arithmetic.
 *
 * <p>Percentage values on Merchant are stored as whole-number percent (e.g.
 * 2.5 means 2.5%). The {@code /100} happens here so callers don't have to
 * remember the convention.
 */
public final class MerchantFeeCalculator {

    private MerchantFeeCalculator() {}

    /** Numerical precision used for the percentage multiply / divide. 12 digits is plenty for currency arithmetic. */
    private static final MathContext MC = new MathContext(12);
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    /** Fee charged to the merchant the moment {@code v} is issued. */
    public static BigDecimal feeForIssued(Merchant m, Voucher v) {
        return compute(m.getFeeIssuedType(),
                       m.getFeeIssuedFixed(),
                       m.getFeeIssuedPercentage(),
                       faceValue(v));
    }

    /** Fee charged to the merchant for redeeming {@code v} in one go — its whole
     *  face value in a single (first) use. A part-used voucher is billed per use
     *  instead: {@code EffectiveFees.feeForRedemptionUse}. */
    public static BigDecimal feeForRedeemed(Merchant m, Voucher v) {
        return compute(m.getFeeRedeemedType(),
                       m.getFeeRedeemedFixed(),
                       m.getFeeRedeemedPercentage(),
                       faceValue(v));
    }

    /**
     * Core arithmetic — exposed package-private so the unit test can hit
     * every branch without having to fabricate a full Voucher entity.
     */
    static BigDecimal compute(Merchant.FeeType type,
                              BigDecimal fixed,
                              BigDecimal percentWholeNumber,
                              BigDecimal faceValue) {
        // The two legs, added. Kept as one call so every existing caller (and
        // the issue side, which is always charged whole) gets exactly what it
        // always did; the redeem side, charged per USE since V61, reads the
        // legs separately through EffectiveFees.feeForRedemptionUse.
        return fixedLeg(type, fixed).add(percentageLeg(type, percentWholeNumber, faceValue));
    }

    /**
     * The FIXED part of a fee: the flat amount for FIXED and
     * FIXED_PLUS_PERCENTAGE, zero for PERCENTAGE (and for no type at all).
     * A balance voucher's redeem-side fixed leg is charged on its FIRST use only.
     */
    static BigDecimal fixedLeg(Merchant.FeeType type, BigDecimal fixed) {
        if (type == Merchant.FeeType.FIXED || type == Merchant.FeeType.FIXED_PLUS_PERCENTAGE) {
            return nz(fixed);
        }
        return BigDecimal.ZERO;
    }

    /**
     * The PERCENTAGE part of a fee on {@code amount}: the percentage of it for
     * PERCENTAGE and FIXED_PLUS_PERCENTAGE, zero for FIXED. Linear in the
     * amount, so charging it on every use of a balance voucher adds up to the
     * same as charging it once on the face value.
     */
    static BigDecimal percentageLeg(Merchant.FeeType type, BigDecimal percentWholeNumber, BigDecimal amount) {
        if (type == Merchant.FeeType.PERCENTAGE || type == Merchant.FeeType.FIXED_PLUS_PERCENTAGE) {
            BigDecimal pct = nz(percentWholeNumber);
            BigDecimal fv = nz(amount);
            // pct is whole-number (2.5 = 2.5%), so divide by 100.
            return fv.multiply(pct, MC).divide(HUNDRED, MC);
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal faceValue(Voucher v) {
        // Voucher.value is the per-issuance face value (V7 snapshot column).
        // Null on pre-V7 legacy rows that never got backfilled; treat as zero
        // so the issued/redeemed counters don't blow up — under-billing those
        // rows is the safe direction.
        return v.getValue() == null ? BigDecimal.ZERO : v.getValue();
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}

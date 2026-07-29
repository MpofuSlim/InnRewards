package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the fee schedule that actually applies to a merchant's vouchers,
 * now that fee config exists at TWO levels (V29):
 *
 * <ol>
 *   <li><b>Merchant-specific RULE fees</b> — the merchant's own override,
 *       managed on {@code /loyalty/rules};</li>
 *   <li><b>Merchant-record fees</b> — the explicit onboarding override
 *       (kept for back-compat). A record left at the onboarding default
 *       (FIXED 0/0) counts as "not configured", so an unconfigured merchant
 *       falls through to…</li>
 *   <li><b>Global RULE fees</b> — the tenant STANDARD every merchant
 *       inherits;</li>
 *   <li>FIXED 0 (no fee) when nothing is configured anywhere.</li>
 * </ol>
 *
 * <p>Issued and redeemed sides resolve independently, mirroring how they are
 * configured independently everywhere else. Rules considered are the
 * time-valid applicable rules for the merchant (merchant-specific first —
 * same ordering the earn path uses); fee lookups deliberately ride the same
 * list so "which config applies" has ONE definition in the codebase.
 *
 * <p>A merchant that wants explicitly ZERO fees while a global standard
 * exists sets a merchant-specific rule with {@code FIXED 0} — level 1 wins.
 *
 * <p>Pure/stateless like {@link MerchantFeeCalculator}, which still owns the
 * arithmetic.
 */
public final class EffectiveFees {

    /** One resolved side (issued or redeemed). */
    record Side(Merchant.FeeType type, BigDecimal fixed, BigDecimal percentage) {
        static final Side NONE = new Side(Merchant.FeeType.FIXED, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final Side issued;
    private final Side redeemed;

    private EffectiveFees(Side issued, Side redeemed) {
        this.issued = issued;
        this.redeemed = redeemed;
    }

    /**
     * Resolve both sides for {@code merchant} from the applicable rules
     * (merchant-specific ordered first, as {@code findApplicable} returns).
     * Rules outside their {@code startsAt}/{@code endsAt} window or inactive
     * are ignored.
     */
    public static EffectiveFees resolve(Merchant merchant, List<LoyaltyRule> applicableRules, Instant now) {
        List<LoyaltyRule> timeValid = applicableRules == null ? List.of() : applicableRules.stream()
                .filter(LoyaltyRule::isActive)
                .filter(r -> r.getStartsAt() == null || !now.isBefore(r.getStartsAt()))
                .filter(r -> r.getEndsAt() == null || !now.isAfter(r.getEndsAt()))
                .toList();
        return new EffectiveFees(
                resolveSide(timeValid, merchant, true),
                resolveSide(timeValid, merchant, false));
    }

    /**
     * In-memory equivalent of {@code LoyaltyRuleRepository.findApplicable}, for
     * callers that already hold every rule in the tenant — the reporting
     * fan-out builds a row per merchant, and re-querying per merchant would be
     * an N+1. Same predicate and same ordering as the query: active rules of
     * {@code type} owned by {@code merchantId} or global, merchant-specific
     * first, newest first.
     */
    public static List<LoyaltyRule> applicable(List<LoyaltyRule> tenantRules, UUID merchantId,
                                               TransactionType type) {
        if (tenantRules == null) {
            return List.of();
        }
        return tenantRules.stream()
                .filter(LoyaltyRule::isActive)
                .filter(r -> r.getTransactionType() == type)
                .filter(r -> r.getMerchantId() == null || r.getMerchantId().equals(merchantId))
                .sorted(Comparator
                        .comparingInt((LoyaltyRule r) -> r.getMerchantId() == null ? 1 : 0)
                        .thenComparing(LoyaltyRule::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static Side resolveSide(List<LoyaltyRule> rules, Merchant m, boolean issuedSide) {
        // 1. merchant-specific rule carrying this side.
        Side fromMerchantRule = firstRuleSide(rules, issuedSide, false);
        if (fromMerchantRule != null) {
            return fromMerchantRule;
        }
        // 2. explicit merchant-record config (anything beyond the FIXED-0/0
        //    onboarding default counts as explicit).
        Side record = issuedSide
                ? new Side(m.getFeeIssuedType(), m.getFeeIssuedFixed(), m.getFeeIssuedPercentage())
                : new Side(m.getFeeRedeemedType(), m.getFeeRedeemedFixed(), m.getFeeRedeemedPercentage());
        if (isConfigured(record)) {
            return record;
        }
        // 3. global rule — the tenant standard.
        Side fromGlobalRule = firstRuleSide(rules, issuedSide, true);
        if (fromGlobalRule != null) {
            return fromGlobalRule;
        }
        // 4. nothing anywhere: no fee.
        return Side.NONE;
    }

    private static Side firstRuleSide(List<LoyaltyRule> rules, boolean issuedSide, boolean global) {
        return rules.stream()
                .filter(r -> global == (r.getMerchantId() == null))
                .map(r -> issuedSide
                        ? new Side(r.getFeeIssuedType(), r.getFeeIssuedFixed(), r.getFeeIssuedPercentage())
                        : new Side(r.getFeeRedeemedType(), r.getFeeRedeemedFixed(), r.getFeeRedeemedPercentage()))
                .filter(s -> s.type() != null)
                .findFirst().orElse(null);
    }

    private static boolean isConfigured(Side s) {
        if (s.type() == null) {
            return false;
        }
        boolean zeroFixed = s.fixed() == null || s.fixed().signum() == 0;
        boolean zeroPct = s.percentage() == null || s.percentage().signum() == 0;
        return !(s.type() == Merchant.FeeType.FIXED && zeroFixed && zeroPct);
    }

    /**
     * True when issuing a voucher at this merchant bills nothing.
     *
     * <p>This is the one that must never happen by accident: issuing is the
     * event we actually charge for, so a merchant with a zero issue fee is a
     * merchant we are running for free. Redemption is allowed to be zero —
     * plenty of commercial arrangements only bill the issue side — so it has a
     * separate accessor and no guard.
     *
     * <p>A zero is a zero however it is expressed: an unconfigured side,
     * FIXED 0, or PERCENTAGE 0 all mean the same thing to the invoice.
     */
    public boolean issuesForFree() {
        return isZero(issued);
    }

    /** True when redeeming bills nothing. Reported by the audit, never refused. */
    public boolean redeemsForFree() {
        return isZero(redeemed);
    }

    private static boolean isZero(Side s) {
        if (s.type() == null) {
            return true;
        }
        boolean zeroFixed = s.fixed() == null || s.fixed().signum() == 0;
        boolean zeroPct = s.percentage() == null || s.percentage().signum() == 0;
        return switch (s.type()) {
            case FIXED -> zeroFixed;
            case PERCENTAGE -> zeroPct;
            case FIXED_PLUS_PERCENTAGE -> zeroFixed && zeroPct;
        };
    }

    public BigDecimal feeForIssued(Voucher v) {
        return MerchantFeeCalculator.compute(issued.type(), issued.fixed(), issued.percentage(), faceValue(v));
    }

    public BigDecimal feeForRedeemed(Voucher v) {
        return MerchantFeeCalculator.compute(redeemed.type(), redeemed.fixed(), redeemed.percentage(), faceValue(v));
    }

    private static BigDecimal faceValue(Voucher v) {
        return v.getValue() == null ? BigDecimal.ZERO : v.getValue();
    }
}

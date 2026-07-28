package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the V29 fee-resolution precedence: merchant rule beats the merchant
 * record, which beats the tenant-wide (global) rule, which beats "no fee".
 *
 * <p>Pure JUnit — {@link EffectiveFees} takes the rules as a list, so no
 * repository or spring context is involved.
 */
class EffectiveFeesTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    /** A merchant left at the onboarding defaults: FIXED 0/0 on both sides. */
    private static Merchant unconfiguredMerchant() {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setTenantId(UUID.randomUUID());
        m.setName("Speke Avenue");
        return m;
    }

    private static LoyaltyRule rule(UUID merchantId) {
        LoyaltyRule r = new LoyaltyRule();
        r.setId(UUID.randomUUID());
        r.setMerchantId(merchantId);
        r.setTransactionType(TransactionType.PURCHASE);
        r.setPointsPerUnit(BigDecimal.ONE);
        r.setMultiplier(BigDecimal.ONE);
        return r;
    }

    private static LoyaltyRule withIssuedFee(LoyaltyRule r, Merchant.FeeType type,
                                             String fixed, String pct) {
        r.setFeeIssuedType(type);
        r.setFeeIssuedFixed(new BigDecimal(fixed));
        r.setFeeIssuedPercentage(new BigDecimal(pct));
        return r;
    }

    private static LoyaltyRule withRedeemedFee(LoyaltyRule r, Merchant.FeeType type,
                                               String fixed, String pct) {
        r.setFeeRedeemedType(type);
        r.setFeeRedeemedFixed(new BigDecimal(fixed));
        r.setFeeRedeemedPercentage(new BigDecimal(pct));
        return r;
    }

    private static Voucher voucher(String faceValue) {
        Voucher v = new Voucher();
        v.setValue(new BigDecimal(faceValue));
        return v;
    }

    @Test
    void nothingConfiguredAnywhereMeansNoFee() {
        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(),
                List.of(rule(null), rule(MERCHANT_ID)), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0");
    }

    @Test
    void unconfiguredMerchantInheritsTheGlobalRuleStandard() {
        // The tenant standard: $0.30 + 2.5% on issue, flat $0.15 on redeem.
        LoyaltyRule global = withRedeemedFee(
                withIssuedFee(rule(null), Merchant.FeeType.FIXED_PLUS_PERCENTAGE, "0.30", "2.5"),
                Merchant.FeeType.FIXED, "0.15", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("2.80");   // 0.30 + 2.50
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0.15");
    }

    @Test
    void merchantRuleOverridesTheGlobalStandard() {
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.PERCENTAGE, "0", "1");

        // findApplicable returns merchant-specific rules first.
        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("2");   // 1%, not 5%
    }

    @Test
    void merchantRuleWithFixedZeroIsTheExplicitOptOut() {
        // The escape hatch documented on /loyalty/rules: a merchant that should
        // pay nothing while a tenant standard exists sets FIXED 0 on its own rule.
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "0", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("0");
    }

    @Test
    void explicitMerchantRecordFeeStillBeatsTheGlobalRule() {
        // Merchants onboarded with their own negotiated fee (pre-V29 behaviour)
        // keep it — the global rule is only the default for the un-negotiated.
        Merchant negotiated = unconfiguredMerchant();
        negotiated.setFeeIssuedType(Merchant.FeeType.FIXED);
        negotiated.setFeeIssuedFixed(new BigDecimal("1.00"));

        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");

        EffectiveFees fees = EffectiveFees.resolve(negotiated, List.of(global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("1.00");
    }

    @Test
    void sidesResolveIndependently() {
        // Merchant overrides only the issue side; the redeem side keeps falling
        // through to the tenant standard.
        LoyaltyRule global = withRedeemedFee(
                withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.50", "0"),
                Merchant.FeeType.FIXED, "0.25", "0");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "0.10", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.10");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0.25");
    }

    @Test
    void inactiveOrOutOfWindowRulesAreIgnored() {
        LoyaltyRule expired = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "9.99", "0");
        expired.setEndsAt(NOW.minus(1, ChronoUnit.DAYS));

        LoyaltyRule notYetLive = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "8.88", "0");
        notYetLive.setStartsAt(NOW.plus(1, ChronoUnit.DAYS));

        LoyaltyRule deactivated = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "7.77", "0");
        deactivated.setActive(false);

        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.20", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(),
                List.of(expired, notYetLive, deactivated, global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.20");
    }

    @Test
    void nullVoucherFaceValueDoesNotBlowUpPercentageFees() {
        // Pre-V7 vouchers carry no face value; under-billing them is the safe
        // direction (same guard MerchantFeeCalculator has).
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(global), NOW);

        assertThat(fees.feeForIssued(new Voucher())).isEqualByComparingTo("0");
    }

    // ── applicable(): the in-memory twin of LoyaltyRuleRepository.findApplicable

    @Test
    void applicableFiltersByTypeAndOwnershipMerchantFirst() {
        LoyaltyRule mine = rule(MERCHANT_ID);
        mine.setCreatedAt(NOW.minus(2, ChronoUnit.DAYS));
        LoyaltyRule global = rule(null);
        global.setCreatedAt(NOW.minus(1, ChronoUnit.DAYS));
        LoyaltyRule someoneElses = rule(UUID.randomUUID());
        LoyaltyRule otherType = rule(MERCHANT_ID);
        otherType.setTransactionType(TransactionType.REFUND);
        LoyaltyRule inactive = rule(MERCHANT_ID);
        inactive.setActive(false);

        List<LoyaltyRule> applicable = EffectiveFees.applicable(
                List.of(global, someoneElses, otherType, inactive, mine),
                MERCHANT_ID, TransactionType.PURCHASE);

        // Merchant-specific first even though the global rule is newer — same
        // ordering the repository query produces.
        assertThat(applicable).containsExactly(mine, global);
    }

    @Test
    void applicableToleratesANullRuleList() {
        assertThat(EffectiveFees.applicable(null, MERCHANT_ID, TransactionType.PURCHASE)).isEmpty();
    }
}

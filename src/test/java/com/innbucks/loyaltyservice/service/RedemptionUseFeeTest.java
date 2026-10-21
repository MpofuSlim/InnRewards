package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redeem-side fee of a BALANCE voucher is charged per use (V61, owner
 * decision 2026-10-21): the PERCENTAGE leg on each use's amount, a FIXED leg on
 * the FIRST use only. The point of that split is that how a voucher is spent
 * never changes what the merchant is billed for it: one go or five uses, the
 * total is today's one-shot fee on the face value.
 */
class RedemptionUseFeeTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private static EffectiveFees fees(Merchant.FeeType type, String fixed, String pct) {
        Merchant m = new Merchant();
        m.setId(UUID.randomUUID());
        m.setTenantId(UUID.randomUUID());
        LoyaltyRule r = new LoyaltyRule();
        r.setId(UUID.randomUUID());
        r.setMerchantId(m.getId());
        r.setTransactionType(TransactionType.PURCHASE);
        r.setPointsPerUnit(BigDecimal.ONE);
        r.setMultiplier(BigDecimal.ONE);
        r.setFeeRedeemedType(type);
        r.setFeeRedeemedFixed(new BigDecimal(fixed));
        r.setFeeRedeemedPercentage(new BigDecimal(pct));
        return EffectiveFees.resolve(m, List.of(r), NOW);
    }

    private static BigDecimal billedOverUses(EffectiveFees fees, String... amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < amounts.length; i++) {
            total = total.add(fees.feeForRedemptionUse(new BigDecimal(amounts[i]), i == 0));
        }
        return total;
    }

    @ParameterizedTest
    @EnumSource(Merchant.FeeType.class)
    void spentInSeveralUses_isBilledTheSameAsSpentInOneGo(Merchant.FeeType type) {
        EffectiveFees fees = fees(type, "0.40", "2.5");
        BigDecimal oneShot = fees.feeForRedeemedFaceValue(new BigDecimal("50.00"));

        assertThat(billedOverUses(fees, "50.00")).isEqualByComparingTo(oneShot);
        assertThat(billedOverUses(fees, "20.00", "12.50", "0.01", "17.49")).isEqualByComparingTo(oneShot);
    }

    @Test
    void percentage_isChargedOnEachUsesAmount() {
        EffectiveFees fees = fees(Merchant.FeeType.PERCENTAGE, "0", "2.5");

        assertThat(fees.feeForRedemptionUse(new BigDecimal("20.00"), true)).isEqualByComparingTo("0.50");
        assertThat(fees.feeForRedemptionUse(new BigDecimal("30.00"), false)).isEqualByComparingTo("0.75");
    }

    @Test
    void fixed_isChargedOnTheFirstUseOnly() {
        EffectiveFees fees = fees(Merchant.FeeType.FIXED, "0.40", "0");

        assertThat(fees.feeForRedemptionUse(new BigDecimal("20.00"), true)).isEqualByComparingTo("0.40");
        assertThat(fees.feeForRedemptionUse(new BigDecimal("30.00"), false)).isEqualByComparingTo("0");
    }

    @Test
    void fixedPlusPercentage_isTheFixedOnceAndThePercentageEveryTime() {
        EffectiveFees fees = fees(Merchant.FeeType.FIXED_PLUS_PERCENTAGE, "0.40", "2.5");

        assertThat(fees.feeForRedemptionUse(new BigDecimal("20.00"), true)).isEqualByComparingTo("0.90");
        assertThat(fees.feeForRedemptionUse(new BigDecimal("30.00"), false)).isEqualByComparingTo("0.75");
    }

    @Test
    void aUseWithNoAmount_pricesItsPercentageAsZero() {
        EffectiveFees fees = fees(Merchant.FeeType.FIXED_PLUS_PERCENTAGE, "0.40", "2.5");

        assertThat(fees.feeForRedemptionUse(null, true)).isEqualByComparingTo("0.40");
        assertThat(fees.feeForRedemptionUse(null, false)).isEqualByComparingTo("0");
    }

    @Test
    void theLegsAddUpToCompute() {
        for (Merchant.FeeType type : Merchant.FeeType.values()) {
            BigDecimal face = new BigDecimal("37.25");
            assertThat(MerchantFeeCalculator.fixedLeg(type, new BigDecimal("0.40"))
                    .add(MerchantFeeCalculator.percentageLeg(type, new BigDecimal("2.5"), face)))
                    .isEqualByComparingTo(MerchantFeeCalculator.compute(type, new BigDecimal("0.40"),
                            new BigDecimal("2.5"), face));
        }
        assertThat(MerchantFeeCalculator.compute(null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN))
                .isEqualByComparingTo("0");
    }
}

package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantFeeCalculatorTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal FACE = new BigDecimal("20.00");

    @Test
    void fixed_returnsFlatAmount_regardlessOfFaceValue() {
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.FIXED, new BigDecimal("0.50"), ZERO, FACE);
        assertThat(fee).isEqualByComparingTo("0.50");
    }

    @Test
    void fixed_facaValueZero_stillReturnsFlat() {
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.FIXED, new BigDecimal("0.50"), ZERO, ZERO);
        assertThat(fee).isEqualByComparingTo("0.50");
    }

    @Test
    void percentage_scalesWithFaceValue() {
        // 2.5% of $20 = $0.50
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.PERCENTAGE, ZERO, new BigDecimal("2.5"), FACE);
        assertThat(fee).isEqualByComparingTo("0.50");
    }

    @Test
    void percentage_faceValueZero_returnsZero() {
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.PERCENTAGE, ZERO, new BigDecimal("2.5"), ZERO);
        assertThat(fee).isEqualByComparingTo("0");
    }

    @Test
    void percentage_ignoresAnyNonZeroFixedThatLeakedThrough() {
        // Defensive: even if the entity ends up with FIXED>0 under
        // PERCENTAGE mode (service-layer validation should prevent this
        // in practice), the calculator only honours the percentage leg.
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.PERCENTAGE, new BigDecimal("99.99"),
                new BigDecimal("1.0"), FACE);
        assertThat(fee).isEqualByComparingTo("0.20");
    }

    @Test
    void fixedPlusPercentage_sumsBothLegs() {
        // $0.30 + 1.5% of $20 = $0.30 + $0.30 = $0.60
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new BigDecimal("0.30"), new BigDecimal("1.5"), FACE);
        assertThat(fee).isEqualByComparingTo("0.60");
    }

    @Test
    void fixedPlusPercentage_zeroPercentage_collapsesToFixed() {
        BigDecimal fee = MerchantFeeCalculator.compute(
                Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new BigDecimal("0.30"), ZERO, FACE);
        assertThat(fee).isEqualByComparingTo("0.30");
    }

    @Test
    void nullType_returnsZero() {
        assertThat(MerchantFeeCalculator.compute(null, new BigDecimal("0.50"),
                new BigDecimal("2.5"), FACE)).isEqualByComparingTo("0");
    }

    @Test
    void nullInputs_treatedAsZero() {
        assertThat(MerchantFeeCalculator.compute(
                Merchant.FeeType.FIXED_PLUS_PERCENTAGE, null, null, null))
                .isEqualByComparingTo("0");
    }
}

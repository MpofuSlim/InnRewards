package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how {@link RuleAdminService#build} (POST /loyalty/rules and merchant
 * onboarding) treats zero fees: allowed on both sides, on a global rule and a
 * merchant rule alike (owner decision, 2026-10-20). The console warns before
 * saving one; omitting a side still means "inherit".
 */
class RuleAdminServiceFeeGuardTest {

    private static Dtos.RuleRequest rule(Dtos.FeeModel issued, Dtos.FeeModel redeemed) {
        return new Dtos.RuleRequest(null, TransactionType.PURCHASE, BigDecimal.ONE, BigDecimal.ONE,
                null, "MAIN", null, null, null, issued, redeemed);
    }

    private static Dtos.FeeModel fixed(String amount) {
        return new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal(amount), BigDecimal.ZERO);
    }

    @Test
    void tenantStandardWithAZeroIssueFee_isAllowed() {
        LoyaltyRule r = RuleAdminService.build(UUID.randomUUID(), null, rule(fixed("0"), null), null, null);

        assertThat(r.getFeeIssuedType()).isEqualTo(Merchant.FeeType.FIXED);
        assertThat(r.getFeeIssuedFixed()).isEqualByComparingTo("0");
    }

    @Test
    void merchantRuleWithAZeroPercentIssueAndRedeemFee_isAllowed() {
        Dtos.FeeModel zeroPct = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE, BigDecimal.ZERO, BigDecimal.ZERO);

        LoyaltyRule r = RuleAdminService.build(UUID.randomUUID(), UUID.randomUUID(),
                rule(zeroPct, zeroPct), null, null);

        assertThat(r.getFeeIssuedType()).isEqualTo(Merchant.FeeType.PERCENTAGE);
        assertThat(r.getFeeIssuedPercentage()).isEqualByComparingTo("0");
        assertThat(r.getFeeRedeemedPercentage()).isEqualByComparingTo("0");
    }

    @Test
    void zeroRedeemFee_isAllowed() {
        // Billing only the issue side is a normal arrangement.
        LoyaltyRule r = RuleAdminService.build(UUID.randomUUID(), null, rule(fixed("0.25"), fixed("0")), null, null);

        assertThat(r.getFeeIssuedFixed()).isEqualByComparingTo("0.25");
        assertThat(r.getFeeRedeemedFixed()).isEqualByComparingTo("0");
    }

    @Test
    void omittingTheIssueFee_isAllowed_thatIsInheritance() {
        // Null means "not configured at this level", which is how a merchant
        // rule inherits the tenant standard — quite different from zero.
        LoyaltyRule r = RuleAdminService.build(UUID.randomUUID(), UUID.randomUUID(), rule(null, null), null, null);

        assertThat(r.getFeeIssuedType()).isNull();
    }
}

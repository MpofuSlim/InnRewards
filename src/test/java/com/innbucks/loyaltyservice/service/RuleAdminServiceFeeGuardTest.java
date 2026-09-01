package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the zero-issue-fee guard on {@link RuleAdminService#build}, which both
 * POST /loyalty/rules and merchant onboarding go through.
 *
 * <p>Issuing is the event the platform bills for, so a zero issue fee is only
 * ever legitimate as a recorded decision on the merchant (fee_waived), never as
 * a number typed into a rule — on the tenant standard it would make every
 * merchant free at once.
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
    void tenantStandardWithAZeroIssueFee_isRefused() {
        // A global rule (merchantId null) priced at zero makes EVERY merchant
        // under the tenant free — the widest possible version of the bug.
        assertThatThrownBy(() -> RuleAdminService.build(UUID.randomUUID(), null, rule(fixed("0"), null), null, null))
                .hasMessageContaining("would run this for free");
    }

    @Test
    void merchantRuleWithAZeroIssueFee_isAlsoRefused() {
        // Otherwise a rule written after onboarding silently undoes the guard
        // that refused the merchant at creation time.
        assertThatThrownBy(() -> RuleAdminService.build(UUID.randomUUID(), UUID.randomUUID(),
                rule(fixed("0"), null), null, null))
                .hasMessageContaining("would run this for free");
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

package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI_USE is no longer issuable, and vouchers already issued as MULTI_USE are
 * still honoured.
 *
 * <p>Owner decision, 2026-09-18: a voucher is worth its face value and is
 * redeemed once. MULTI_USE never had money semantics that worked — {@code value}
 * is a face amount and {@code usesRemaining} a bare counter, with no remaining
 * balance anywhere, so the redeem response handed the till the FULL face value
 * on every use. A "$5, three uses" voucher was worth $5 three times over.
 *
 * <p>These cases sit on {@code resolveUsageLimit} because it is the single gate
 * all three issue paths share — {@code issue}, {@code issueBulk}, and
 * {@code VoucherPurchaseService.create}, which runs the same contract at order
 * creation so a customer is never asked to pay for a voucher that issue would
 * then refuse.
 */
class MultiUseRetirementTest {

    @Test
    void issuingMultiUseIsRefusedByName() {
        assertThatThrownBy(() -> VoucherService.resolveUsageLimit(Voucher.VoucherType.MULTI_USE, 3))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("MULTI_USE_RETIRED"));
    }

    @Test
    void multiUseIsRefusedEvenWithoutAUsageLimit() {
        // The type alone is the refusal, so a caller cannot reach it by leaving
        // the limit off.
        assertThatThrownBy(() -> VoucherService.resolveUsageLimit(Voucher.VoucherType.MULTI_USE, null))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("MULTI_USE_RETIRED"));
    }

    @Test
    void multiUseIsRefused_notSilentlyDowngradedToOneUse() {
        // A caller asking for three uses has priced something. Quietly giving
        // them one is the kind of change that surfaces at a till, so the request
        // is refused rather than corrected.
        assertThatThrownBy(() -> VoucherService.resolveUsageLimit(Voucher.VoucherType.MULTI_USE, 3))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    void aUsageLimitAboveOneIsRefusedWhateverTheType() {
        // Including the default (null) type, which is SINGLE_USE — there is no
        // longer any type this limit could have meant.
        assertThatThrownBy(() -> VoucherService.resolveUsageLimit(null, 5))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USAGE_LIMIT_CONFLICT"));
        assertThatThrownBy(() -> VoucherService.resolveUsageLimit(Voucher.VoucherType.SINGLE_USE, 2))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("USAGE_LIMIT_CONFLICT"));
    }

    @Test
    void theOrdinaryShapesStillIssueAndAlwaysGetExactlyOneUse() {
        assertThat(VoucherService.resolveUsageLimit(null, null)).isEqualTo(1);
        assertThat(VoucherService.resolveUsageLimit(Voucher.VoucherType.SINGLE_USE, null)).isEqualTo(1);
        assertThat(VoucherService.resolveUsageLimit(Voucher.VoucherType.SINGLE_USE, 1)).isEqualTo(1);
        assertThat(VoucherService.voucherTypeOrDefault(null)).isEqualTo(Voucher.VoucherType.SINGLE_USE);
    }

    @Test
    void theMULTI_USEConstantSTAYSOnTheEnum() {
        // Deleting it would be an @Enumerated(EnumType.STRING) removal: every
        // voucher already issued as MULTI_USE still holds the string, and
        // Hibernate would throw per row at query execution on every read path
        // that touches them — with no compile, boot or CI signal. It may go once
        // none remain, with a migration and a narrowed CHECK constraint.
        assertThat(Voucher.VoucherType.valueOf("MULTI_USE")).isNotNull();
        assertThat(Voucher.VoucherType.values()).hasSize(2);
    }
}

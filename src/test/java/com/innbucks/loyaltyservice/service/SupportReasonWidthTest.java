package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.PointsLedger;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A support reason is stored where the underlying action already stores one,
 * so each {@code @Size} is a promise about a COLUMN, prefix included. These are
 * the pairs CLAUDE.md says to keep in lock-step; this test is what keeps them
 * there — widen a cap past its column and a legitimate request fails at INSERT
 * instead of at validation.
 *
 * <p>The merchant endpoints this reuses accept reasons up to 1000 characters
 * into the same columns — the latent bug the support caps are sized to avoid.
 */
class SupportReasonWidthTest {

    private static int columnLength(Class<?> entity, String field) throws Exception {
        return entity.getDeclaredField(field).getAnnotation(Column.class).length();
    }

    /** A record component's annotations propagate to its private field. */
    private static int sizeMax(Class<? extends Record> dto, String component) throws Exception {
        return dto.getDeclaredField(component).getAnnotation(Size.class).max();
    }

    @Test
    @DisplayName("an adjustment reason fits the transaction reference — and still fits after a reversal prefixes REV-")
    void adjustReason_fitsReferenceEvenOnceReversed() throws Exception {
        int reference = columnLength(LoyaltyTransaction.class, "reference");
        int cap = sizeMax(SupportDtos.AdjustRequest.class, "reason");

        assertThat(cap).isEqualTo(SupportDtos.MAX_ADJUST_REASON);
        assertThat("REV-".length() + cap).isLessThanOrEqualTo(reference);
    }

    @Test
    @DisplayName("an adjustment reason fits the ledger entry after its adjust: prefix")
    void adjustReason_fitsLedger() throws Exception {
        assertThat("adjust:".length() + sizeMax(SupportDtos.AdjustRequest.class, "reason"))
                .isLessThanOrEqualTo(columnLength(PointsLedger.class, "reason"));
    }

    @Test
    @DisplayName("a reversal reason fits the ledger entry after its reverse: prefix")
    void reverseReason_fitsLedger() throws Exception {
        int cap = sizeMax(SupportDtos.ReverseRequest.class, "reason");

        assertThat(cap).isEqualTo(SupportDtos.MAX_REVERSE_REASON);
        assertThat("reverse:".length() + cap).isLessThanOrEqualTo(columnLength(PointsLedger.class, "reason"));
    }

    @Test
    @DisplayName("the sign-out revocation reason is clamped to the refresh-token column")
    void revokeReason_fitsRefreshTokenColumn() throws Exception {
        assertThat(SupportActionService.MAX_REVOKE_REASON)
                .isEqualTo(columnLength(LoyaltyRefreshToken.class, "revokedReason"));
    }
}

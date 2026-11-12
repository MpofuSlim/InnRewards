package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.FeeSchedule;
import com.innbucks.loyaltyservice.entity.Merchant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlatformFeeBook}: the latest row of each scope wins, a tombstone
 * withdraws its scope, and a tenant's default and a merchant's own schedule
 * never stand in for each other.
 */
class PlatformFeeBookTest {

    private static final Instant T0 = Instant.parse("2026-11-12T08:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    private static FeeSchedule live(UUID merchantId, String issuedFixed, Instant at) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setMerchantId(merchantId);
        s.setFeeIssuedType(Merchant.FeeType.FIXED);
        s.setFeeIssuedFixed(new BigDecimal(issuedFixed));
        s.setFeeIssuedPercentage(BigDecimal.ZERO);
        s.setFeeRedeemedType(Merchant.FeeType.FIXED);
        s.setFeeRedeemedFixed(BigDecimal.ZERO);
        s.setFeeRedeemedPercentage(BigDecimal.ZERO);
        s.setSetBy("finance@innbucks.co.zw");
        s.setCreatedAt(at);
        return s;
    }

    private static FeeSchedule tombstone(UUID merchantId, Instant at) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setMerchantId(merchantId);
        s.setCleared(true);
        s.setSetBy("finance@innbucks.co.zw");
        s.setCreatedAt(at);
        return s;
    }

    @Test
    void anEmptyOrMissingListIsTheEmptyBook() {
        assertThat(PlatformFeeBook.of(null)).isSameAs(PlatformFeeBook.EMPTY);
        assertThat(PlatformFeeBook.of(List.of())).isSameAs(PlatformFeeBook.EMPTY);
        assertThat(PlatformFeeBook.EMPTY.tenantSchedule(TENANT)).isEmpty();
        assertThat(PlatformFeeBook.EMPTY.merchantSchedule(MERCHANT)).isEmpty();
    }

    @Test
    void theNewestRowOfEachScopeWins_whateverOrderTheHistoryArrivesIn() {
        List<FeeSchedule> history = new ArrayList<>(List.of(
                live(null, "0.10", T0),
                live(null, "0.20", T0.plusSeconds(60)),
                live(MERCHANT, "0.30", T0.plusSeconds(30)),
                live(MERCHANT, "0.40", T0.plusSeconds(90))));
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(history);
            PlatformFeeBook book = PlatformFeeBook.of(history);

            assertThat(book.tenantSchedule(TENANT).orElseThrow().getFeeIssuedFixed()).isEqualByComparingTo("0.20");
            assertThat(book.merchantSchedule(MERCHANT).orElseThrow().getFeeIssuedFixed())
                    .isEqualByComparingTo("0.40");
        }
    }

    @Test
    void twoRowsAtTheSameInstant_resolveTheWayTheRepositoryOrdersThem() {
        // The query orders created_at DESC, id DESC; the book breaks a tie the same way.
        FeeSchedule a = live(null, "0.10", T0);
        FeeSchedule b = live(null, "0.20", T0);
        FeeSchedule higherId = a.getId().compareTo(b.getId()) > 0 ? a : b;

        assertThat(PlatformFeeBook.of(List.of(a, b)).tenantSchedule(TENANT).orElseThrow()).isSameAs(higherId);
        assertThat(PlatformFeeBook.of(List.of(b, a)).tenantSchedule(TENANT).orElseThrow()).isSameAs(higherId);
    }

    @Test
    void aTombstoneWithdrawsItsScope_andALaterScheduleBringsItBack() {
        PlatformFeeBook withdrawn = PlatformFeeBook.of(List.of(
                live(MERCHANT, "0.30", T0), tombstone(MERCHANT, T0.plusSeconds(60))));
        PlatformFeeBook setAgain = PlatformFeeBook.of(List.of(
                live(MERCHANT, "0.30", T0), tombstone(MERCHANT, T0.plusSeconds(60)),
                live(MERCHANT, "0.50", T0.plusSeconds(120))));

        assertThat(withdrawn.merchantSchedule(MERCHANT)).isEmpty();
        assertThat(setAgain.merchantSchedule(MERCHANT).orElseThrow().getFeeIssuedFixed())
                .isEqualByComparingTo("0.50");
    }

    @Test
    void aTenantDefaultAndAMerchantsOwnScheduleAreSeparateScopes() {
        PlatformFeeBook tenantOnly = PlatformFeeBook.of(List.of(live(null, "0.20", T0)));
        PlatformFeeBook merchantOnly = PlatformFeeBook.of(List.of(live(MERCHANT, "0.30", T0)));

        assertThat(tenantOnly.merchantSchedule(MERCHANT)).isEmpty();
        assertThat(merchantOnly.tenantSchedule(TENANT)).isEmpty();
        // Withdrawing the merchant's own schedule never withdraws the tenant default.
        PlatformFeeBook both = PlatformFeeBook.of(List.of(
                live(null, "0.20", T0), live(MERCHANT, "0.30", T0), tombstone(MERCHANT, T0.plusSeconds(1))));
        assertThat(both.tenantSchedule(TENANT)).isPresent();
        assertThat(both.merchantSchedule(MERCHANT)).isEmpty();
    }

    @Test
    void theNoneSource_pricesWithTheEmptyBook_withoutReadingAnything() {
        assertThat(PlatformFeeBookSource.NONE.forTenant(TENANT)).isSameAs(PlatformFeeBook.EMPTY);
        assertThat(PlatformFeeBookSource.NONE.forTenant(null)).isSameAs(PlatformFeeBook.EMPTY);
        assertThat(PlatformFeeBookSource.NONE.forTenants(List.of(TENANT))).isSameAs(PlatformFeeBook.EMPTY);
    }
}

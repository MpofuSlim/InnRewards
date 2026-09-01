package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.RedemptionRate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.RedemptionRateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The redemption formula's arithmetic and resolution — pure Mockito, no Spring
 * (the sandbox has no Docker for Testcontainers; see CLAUDE.md). The rounding
 * and the "no rate configured" failure are what the money correctness rests on.
 */
class RedemptionRateServiceTest {

    private final RedemptionRateRepository repo = mock(RedemptionRateRepository.class);
    private final RedemptionRateService service = new RedemptionRateService(repo);

    private static RedemptionRate rate(String pointsPerUnit, String currency) {
        RedemptionRate r = new RedemptionRate();
        r.setPointsPerUnit(new BigDecimal(pointsPerUnit));
        r.setCurrency(currency);
        r.setEffectiveFrom(Instant.EPOCH);
        return r;
    }

    private void currentIs(String ppu, String currency) {
        when(repo.currentRate(eq(currency), any())).thenReturn(Optional.of(rate(ppu, currency)));
    }

    @Test
    @DisplayName("valueOf: points -> currency at the platform rate, 4dp HALF_UP")
    void valueOf() {
        currentIs("100", "USD");
        // 250 points at 100 pts/$1 = $2.50
        assertThat(service.valueOf(new BigDecimal("250"), "USD")).isEqualByComparingTo("2.5000");
        // 1 point = $0.01
        assertThat(service.valueOf(BigDecimal.ONE, "USD")).isEqualByComparingTo("0.0100");
        // A rate that doesn't divide evenly rounds to 4dp: 100 / 3 pts-per-unit...
    }

    @Test
    @DisplayName("valueOf: an odd rate rounds the value to 4dp HALF_UP")
    void valueOfRounds() {
        currentIs("3", "USD"); // 3 points buys $1
        // 10 points / 3 = $3.3333...
        assertThat(service.valueOf(new BigDecimal("10"), "USD")).isEqualByComparingTo("3.3333");
    }

    @Test
    @DisplayName("valueOf: null points is a clean zero, not an NPE")
    void valueOfNull() {
        assertThat(service.valueOf(null, "USD")).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("pointsFor: currency -> whole points at the platform rate, HALF_UP")
    void pointsFor() {
        currentIs("100", "USD");
        // $5.00 at 100 pts/$1 = 500 points
        assertThat(service.pointsFor(new BigDecimal("5.00"), "USD")).isEqualByComparingTo("500");
        // $2.50 = 250 points
        assertThat(service.pointsFor(new BigDecimal("2.50"), "USD")).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("pointsFor: rounds to a WHOLE number (points are integral)")
    void pointsForWhole() {
        currentIs("100", "USD");
        // $1.005 * 100 = 100.5 -> 101 (HALF_UP)
        assertThat(service.pointsFor(new BigDecimal("1.005"), "USD")).isEqualByComparingTo("101");
        // $1.004 * 100 = 100.4 -> 100
        assertThat(service.pointsFor(new BigDecimal("1.004"), "USD")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("pointsFor: a non-positive amount is refused")
    void pointsForRejectsNonPositive() {
        assertThatThrownBy(() -> service.pointsFor(BigDecimal.ZERO, "USD"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("currentRate: throws a clear error when no rate is configured for the currency")
    void currentRateMissing() {
        when(repo.currentRate(eq("EUR"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentRate("EUR"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("No redemption rate is configured for EUR");
    }

    @Test
    @DisplayName("currency is normalized: blank -> USD, lower-case -> upper")
    void currencyNormalized() {
        currentIs("100", "USD");
        assertThat(service.valueOf(new BigDecimal("100"), null)).isEqualByComparingTo("1.0000");
        assertThat(service.valueOf(new BigDecimal("100"), "usd")).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("setRate: refuses a zero or negative rate")
    void setRateRejectsNonPositive() {
        assertThatThrownBy(() -> service.setRate(BigDecimal.ZERO, "USD", null, null, null))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> service.setRate(new BigDecimal("-5"), "USD", null, null, null))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @DisplayName("setRate: null effectiveFrom means now; persists the row")
    void setRatePersists() {
        when(repo.save(any(RedemptionRate.class))).thenAnswer(inv -> inv.getArgument(0));
        RedemptionRate saved = service.setRate(new BigDecimal("120"), "usd", null, null, "Q4");
        assertThat(saved.getPointsPerUnit()).isEqualByComparingTo("120");
        assertThat(saved.getCurrency()).isEqualTo("USD"); // normalized
        assertThat(saved.getEffectiveFrom()).isNotNull();
        assertThat(saved.getNote()).isEqualTo("Q4");
    }
}

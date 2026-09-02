package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Pins the FX substrate contract (multi-currency PR 1): fail-closed resolution
 * (NO_FX_RATE, never a silent 1.0), USD-identity conversion with no table
 * read, HALF_UP money-scale rounding, the immutable base, and the setRate
 * guard ladder (BAD_RATE → sanity band → force-needs-note), including that a
 * tenant override is stamped with its tenantId and banded against the rate it
 * overrides.
 */
class ExchangeRateServiceTest {

    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD,ZAR,ZWG", "USD");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();

    private final ExchangeRateRepository repo = mock(ExchangeRateRepository.class);
    private final ExchangeRateService fx =
            new ExchangeRateService(repo, CURRENCIES, new BigDecimal("25"));

    private static ExchangeRate rate(String ccy, String perUsd) {
        ExchangeRate r = new ExchangeRate();
        r.setId(UUID.randomUUID());
        r.setCurrency(ccy);
        r.setRatePerUsd(new BigDecimal(perUsd));
        r.setEffectiveFrom(Instant.now().minusSeconds(3600));
        r.setSource(ExchangeRate.Source.ADMIN);
        return r;
    }

    // ---- resolution -------------------------------------------------------

    @Test
    void currentRate_missing_failsClosedWithNoFxRate() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fx.currentRate(null, "ZWG"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_FX_RATE"))
                .hasMessageContaining("USD→ZWG");
    }

    @Test
    void currentRate_base_isRefusedNotResolved() {
        assertThatThrownBy(() -> fx.currentRate(null, "USD"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_BASE_IMMUTABLE"));
        verifyNoInteractions(repo);
    }

    @Test
    void currentRate_unsupportedCurrency_isRefusedByTheAllowlist() {
        assertThatThrownBy(() -> fx.currentRate(null, "GBP"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verifyNoInteractions(repo);
    }

    // ---- conversion -------------------------------------------------------

    @Test
    void toBase_usd_isIdentityRescaled_noTableRead() {
        assertThat(fx.toBase(TENANT, new BigDecimal("100"), "usd"))
                .isEqualTo(new BigDecimal("100.0000"));
        verifyNoInteractions(repo);
    }

    @Test
    void toBase_dividesByRatePerUsd_halfUpAtMoneyScale() {
        when(repo.currentRate(any(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));
        // ZWG 100 → 100 / 26.7 = 3.74531… → 3.7453
        assertThat(fx.toBase(TENANT, new BigDecimal("100"), "ZWG"))
                .isEqualTo(new BigDecimal("3.7453"));
    }

    @Test
    void fromBase_multipliesByRatePerUsd_halfUpAtMoneyScale() {
        when(repo.currentRate(any(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));
        // USD 10 → 267.0000 ZWG
        assertThat(fx.fromBase(TENANT, new BigDecimal("10"), "ZWG"))
                .isEqualTo(new BigDecimal("267.0000"));
    }

    @Test
    void conversion_refusesNegativeAmounts() {
        assertThatThrownBy(() -> fx.toBase(TENANT, new BigDecimal("-1"), "USD"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BAD_AMOUNT"));
        assertThatThrownBy(() -> fx.fromBase(TENANT, null, "USD"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BAD_AMOUNT"));
    }

    // ---- setRate ----------------------------------------------------------

    @Test
    void setRate_firstRateForScope_savesAdminRowWithDefaults() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        ExchangeRate saved = fx.setRate(null, " zwg ", new BigDecimal("26.7"),
                null, false, OPERATOR, "RBZ interbank");

        assertThat(saved.getTenantId()).isNull();               // platform (bank-default) scope
        assertThat(saved.getCurrency()).isEqualTo("ZWG");       // normalized
        assertThat(saved.getSource()).isEqualTo(ExchangeRate.Source.ADMIN);
        assertThat(saved.getCreatedBy()).isEqualTo(OPERATOR);
        assertThat(saved.getEffectiveFrom()).isNotNull();       // null → now
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void setRate_tenantScope_stampsTheTenantId() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        ExchangeRate saved = fx.setRate(TENANT, "ZWG", new BigDecimal("27.5"),
                null, false, OPERATOR, "Our settlement bank's rate");

        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        // The band was checked against the TENANT-scope resolution (which falls
        // back to the bank rate) — i.e. exactly what this row will override.
        verify(repo).currentRate(eq(TENANT), eq("ZWG"), any(Instant.class));
    }

    @Test
    void setRate_base_isImmutable() {
        assertThatThrownBy(() -> fx.setRate(null, "USD", BigDecimal.ONE, null, false, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_BASE_IMMUTABLE"));
        verify(repo, never()).save(any());
    }

    @Test
    void setRate_zeroOrNegative_isRefused() {
        for (BigDecimal bad : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-5"), null}) {
            assertThatThrownBy(() -> fx.setRate(null, "ZWG", bad, null, false, OPERATOR, null))
                    .isInstanceOfSatisfying(LoyaltyException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo("BAD_RATE"));
        }
        verify(repo, never()).save(any());
    }

    @Test
    void setRate_unsupportedCurrency_isRefused() {
        assertThatThrownBy(() -> fx.setRate(null, "GBP", BigDecimal.ONE, null, false, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verify(repo, never()).save(any());
    }

    @Test
    void setRate_withinBand_savesWithoutForce() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any()))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        // 30 vs 26.7 = 12.36% change, inside the ±25% band.
        assertThat(fx.setRate(null, "ZWG", new BigDecimal("30"), null, false, OPERATOR, null)
                .getRatePerUsd()).isEqualByComparingTo("30");
    }

    @Test
    void setRate_outOfBand_isRefusedWithoutForce() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any()))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));

        // 40 vs 26.7 = 49.81% change, outside the ±25% band.
        assertThatThrownBy(() -> fx.setRate(null, "ZWG", new BigDecimal("40"), null, false, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_RATE_OUT_OF_BAND"))
                .hasMessageContaining("49.81");
        verify(repo, never()).save(any());
    }

    @Test
    void setRate_forceWithoutNote_isRefused() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any()))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));

        assertThatThrownBy(() -> fx.setRate(null, "ZWG", new BigDecimal("40"), null, true, OPERATOR, "  "))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_FORCE_NEEDS_NOTE"));
        verify(repo, never()).save(any());
    }

    @Test
    void setRate_forceWithNote_goesThroughTheBand() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any()))
                .thenReturn(Optional.of(rate("ZWG", "26.700000")));
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        ExchangeRate saved = fx.setRate(null, "ZWG", new BigDecimal("40"), null, true, OPERATOR,
                "Deliberate devaluation per RBZ circular");
        assertThat(saved.getRatePerUsd()).isEqualByComparingTo("40");
    }

    @Test
    void setRate_bandDisabled_acceptsAnyChange() {
        ExchangeRateService noBand = new ExchangeRateService(repo, CURRENCIES, BigDecimal.ZERO);
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(noBand.setRate(null, "ZWG", new BigDecimal("400"), null, false, OPERATOR, null)
                .getRatePerUsd()).isEqualByComparingTo("400");
        // Band off → the current rate is never even consulted.
        verify(repo, never()).currentRate(nullable(UUID.class), anyString(), any());
    }

    // ---- clearOverride (V39) ----------------------------------------------

    @Test
    void clearOverride_writesATombstoneWithNoRate() {
        when(repo.findInForceForTenant(eq(TENANT), eq("ZWG"), any(Instant.class), any()))
                .thenReturn(java.util.List.of(rate("ZWG", "27.500000")));
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        ExchangeRate cleared = fx.clearOverride(TENANT, "zwg", null, OPERATOR, "back to bank rate");

        assertThat(cleared.isCleared()).isTrue();
        assertThat(cleared.getRatePerUsd()).as("a revocation carries no rate").isNull();
        assertThat(cleared.getTenantId()).isEqualTo(TENANT);
        assertThat(cleared.getCurrency()).isEqualTo("ZWG");
        assertThat(cleared.getCreatedBy()).isEqualTo(OPERATOR);
    }

    @Test
    void clearOverride_withNoOverrideInForce_isRefused() {
        when(repo.findInForceForTenant(eq(TENANT), eq("ZWG"), any(Instant.class), any()))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> fx.clearOverride(TENANT, "ZWG", null, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_NO_OVERRIDE"));
        verify(repo, never()).save(any());
    }

    @Test
    void clearOverride_whenAlreadyCleared_isRefused_soTombstonesDontPileUp() {
        ExchangeRate existingTombstone = rate("ZWG", "1");
        existingTombstone.setRatePerUsd(null);
        existingTombstone.setCleared(true);
        when(repo.findInForceForTenant(eq(TENANT), eq("ZWG"), any(Instant.class), any()))
                .thenReturn(java.util.List.of(existingTombstone));

        assertThatThrownBy(() -> fx.clearOverride(TENANT, "ZWG", null, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_NO_OVERRIDE"));
        verify(repo, never()).save(any());
    }

    @Test
    void clearOverride_onPlatformScope_isRefused() {
        assertThatThrownBy(() -> fx.clearOverride(null, "ZWG", null, OPERATOR, null))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FX_CANNOT_CLEAR_PLATFORM"));
        verifyNoInteractions(repo);
    }

    @Test
    void clearOverride_checksTheOverrideAtTheSCHEDULEDInstant_notNow() {
        // A clear scheduled for next week must be validated against what will be
        // in force THEN, not what is in force today.
        Instant nextWeek = Instant.now().plusSeconds(7 * 24 * 3600);
        when(repo.findInForceForTenant(eq(TENANT), eq("ZWG"), eq(nextWeek), any()))
                .thenReturn(java.util.List.of(rate("ZWG", "27.500000")));
        when(repo.save(any(ExchangeRate.class))).thenAnswer(inv -> inv.getArgument(0));

        ExchangeRate cleared = fx.clearOverride(TENANT, "ZWG", nextWeek, OPERATOR, "scheduled revert");

        assertThat(cleared.getEffectiveFrom()).isEqualTo(nextWeek);
        verify(repo).findInForceForTenant(eq(TENANT), eq("ZWG"), eq(nextWeek), any());
    }
}

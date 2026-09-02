package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.ExchangeRate;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Pins the override-precedence contract of
 * {@link ExchangeRateRepository#currentRate}: the bank (platform) rate applies
 * ONLY when nobody set one —
 *
 * <ol>
 *   <li>a tenant's own rate overrides everything for that tenant;</li>
 *   <li>a platform-ADMIN rate overrides the automated FEED, regardless of
 *       which is newer — the feed never silently out-dates a human decision;</li>
 *   <li>the FEED default applies last.</li>
 * </ol>
 */
class ExchangeRateResolutionPrecedenceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String ZWG = "ZWG";
    private static final Instant NOW = Instant.now();

    private final ExchangeRateRepository repo = mock(ExchangeRateRepository.class);

    private ExchangeRateResolutionPrecedenceTest useRealResolution() {
        when(repo.currentRate(nullable(UUID.class), anyString(), any())).thenCallRealMethod();
        return this;
    }

    private static ExchangeRate rate(UUID tenantId, ExchangeRate.Source source, String perUsd) {
        ExchangeRate r = new ExchangeRate();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setCurrency(ZWG);
        r.setRatePerUsd(new BigDecimal(perUsd));
        r.setEffectiveFrom(NOW.minusSeconds(60));
        r.setSource(source);
        return r;
    }

    @Test
    void tenantOverride_beatsEveryPlatformRow() {
        useRealResolution();
        ExchangeRate override = rate(TENANT, ExchangeRate.Source.ADMIN, "27.500000");
        when(repo.findInForceForTenant(eq(TENANT), eq(ZWG), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(override));

        Optional<ExchangeRate> got = repo.currentRate(TENANT, ZWG, NOW);

        assertThat(got).contains(override);
        // Short-circuits: the platform scopes are never even consulted.
        verify(repo, never()).findInForcePlatform(anyString(), any(), any(), any());
    }

    @Test
    void noOverride_platformAdminBeatsFeed_regardlessOfRecency() {
        useRealResolution();
        ExchangeRate admin = rate(null, ExchangeRate.Source.ADMIN, "26.700000");
        when(repo.findInForceForTenant(eq(TENANT), eq(ZWG), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of());
        when(repo.findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.ADMIN), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(admin));

        assertThat(repo.currentRate(TENANT, ZWG, NOW)).contains(admin);
        // ADMIN found → FEED never consulted, so even a newer feed row can't win.
        verify(repo, never()).findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.FEED), any(), any());
    }

    @Test
    void feedIsTheDefault_whenNobodySetARate() {
        useRealResolution();
        ExchangeRate feed = rate(null, ExchangeRate.Source.FEED, "26.900000");
        when(repo.findInForceForTenant(eq(TENANT), eq(ZWG), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of());
        when(repo.findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.ADMIN), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of());
        when(repo.findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.FEED), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(feed));

        assertThat(repo.currentRate(TENANT, ZWG, NOW)).contains(feed);
    }

    @Test
    void nullTenant_resolvesPlatformScopesOnly() {
        useRealResolution();
        ExchangeRate admin = rate(null, ExchangeRate.Source.ADMIN, "26.700000");
        when(repo.findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.ADMIN), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(admin));

        assertThat(repo.currentRate(null, ZWG, NOW)).contains(admin);
        verify(repo, never()).findInForceForTenant(any(), anyString(), any(), any());
    }

    @Test
    void nothingAnywhere_resolvesEmpty_theServiceTurnsThisIntoNoFxRate() {
        useRealResolution();
        when(repo.findInForceForTenant(any(), anyString(), any(), any())).thenReturn(List.of());
        when(repo.findInForcePlatform(anyString(), any(), any(), any())).thenReturn(List.of());

        assertThat(repo.currentRate(TENANT, ZWG, NOW)).isEmpty();
    }

    @Test
    void clearedTenantRow_isATombstone_andFallsThroughToThePlatformRate() {
        useRealResolution();
        // The tenant deliberately went back to the bank rate: their latest
        // in-force row is a revocation, not a rate.
        ExchangeRate tombstone = rate(TENANT, ExchangeRate.Source.ADMIN, "1");
        tombstone.setRatePerUsd(null);
        tombstone.setCleared(true);
        ExchangeRate bank = rate(null, ExchangeRate.Source.ADMIN, "26.700000");
        when(repo.findInForceForTenant(eq(TENANT), eq(ZWG), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(tombstone));
        when(repo.findInForcePlatform(eq(ZWG), eq(ExchangeRate.Source.ADMIN), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(bank));

        assertThat(repo.currentRate(TENANT, ZWG, NOW)).contains(bank);
    }

    @Test
    void aNewerOverrideAfterAClear_winsAgain() {
        useRealResolution();
        // "latest in force wins" means an override set AFTER a clear is live
        // again — the tombstone only suppresses while it is the newest row.
        ExchangeRate reinstated = rate(TENANT, ExchangeRate.Source.ADMIN, "27.500000");
        when(repo.findInForceForTenant(eq(TENANT), eq(ZWG), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(reinstated));

        assertThat(repo.currentRate(TENANT, ZWG, NOW)).contains(reinstated);
        verify(repo, never()).findInForcePlatform(anyString(), any(), any(), any());
    }
}

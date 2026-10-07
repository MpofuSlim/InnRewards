package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batching loop, the caps and the failure isolation, with mocked
 * repositories. The predicates themselves (what is dead, what must survive)
 * are pinned against real Postgres by {@link TokenRetentionPurgeIT}.
 */
class TokenRetentionPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:25:00Z");

    private QrTokenRepository qrs;
    private LoyaltyRefreshTokenRepository refresh;
    private SimpleMeterRegistry registry;
    private LoyaltyMetrics metrics;

    @BeforeEach
    void setUp() {
        qrs = mock(QrTokenRepository.class);
        refresh = mock(LoyaltyRefreshTokenRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new LoyaltyMetrics(registry);
    }

    private TokenRetentionPurgeJob job(boolean enabled, int qrDays, int refreshDays, int batch, int maxBatches) {
        return new TokenRetentionPurgeJob(qrs, refresh, metrics, TransactionOperations.withoutTransaction(),
                enabled, qrDays, refreshDays, batch, maxBatches);
    }

    private double purged(String table) {
        return registry.get("loyalty.retention.purged").tag("table", table).counter().count();
    }

    @Test
    @DisplayName("the purged-rows counter exists at 0 for both tables before any run")
    void metricIsRegisteredAtZero() {
        assertThat(purged(LoyaltyMetrics.TABLE_QR_TOKENS)).isZero();
        assertThat(purged(LoyaltyMetrics.TABLE_REFRESH_TOKENS)).isZero();
    }

    @Test
    @DisplayName("drains in batches and stops at the first short batch")
    void drainsUntilAShortBatch() {
        when(qrs.purgeFinishedBefore(any(), eq(10))).thenReturn(10, 10, 3);
        when(refresh.purgeChainsEndedBefore(any(), eq(10))).thenReturn(4);

        TokenRetentionPurgeJob.Outcome out = job(true, 90, 30, 10, 50).run(NOW);

        assertThat(out.qrTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(23, 3, true));
        assertThat(out.refreshTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(4, 1, true));
        verify(qrs, times(3)).purgeFinishedBefore(NOW.minus(90, ChronoUnit.DAYS), 10);
        verify(refresh).purgeChainsEndedBefore(NOW.minus(30, ChronoUnit.DAYS), 10);
        assertThat(purged(LoyaltyMetrics.TABLE_QR_TOKENS)).isEqualTo(23);
        assertThat(purged(LoyaltyMetrics.TABLE_REFRESH_TOKENS)).isEqualTo(4);
    }

    @Test
    @DisplayName("an empty table costs one query and reports drained")
    void emptyTable() {
        TokenRetentionPurgeJob.Outcome out = job(true, 90, 90, 1000, 500).run(NOW);
        assertThat(out.qrTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(0, 1, true));
        assertThat(out.refreshTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(0, 1, true));
    }

    @Test
    @DisplayName("a run stops at max-batches-per-run and reports the table NOT drained")
    void stopsAtTheBatchCap() {
        when(qrs.purgeFinishedBefore(any(), anyInt())).thenReturn(5);

        TokenRetentionPurgeJob.Outcome out = job(true, 90, 90, 5, 3).run(NOW);

        assertThat(out.qrTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(15, 3, false));
        verify(qrs, times(3)).purgeFinishedBefore(any(), anyInt());
    }

    @Test
    @DisplayName("a failing table is logged, keeps what it committed, and does not stop the other table")
    void failureIsIsolatedPerTable() {
        when(qrs.purgeFinishedBefore(any(), anyInt()))
                .thenReturn(2)
                .thenThrow(new IllegalStateException("db down"));
        when(refresh.purgeChainsEndedBefore(any(), anyInt())).thenReturn(1);

        TokenRetentionPurgeJob.Outcome out = job(true, 90, 90, 2, 10).run(NOW);

        assertThat(out.qrTokens()).isEqualTo(new TokenRetentionPurgeJob.TableOutcome(2, 1, false));
        assertThat(out.refreshTokens().complete()).isTrue();
        assertThat(purged(LoyaltyMetrics.TABLE_QR_TOKENS)).isEqualTo(2);
        assertThat(purged(LoyaltyMetrics.TABLE_REFRESH_TOKENS)).isEqualTo(1);
    }

    @Test
    @DisplayName("disabled: the scheduled entry point touches nothing")
    void disabledDoesNothing() {
        job(false, 90, 90, 1000, 500).purge();
        verify(qrs, never()).purgeFinishedBefore(any(), anyInt());
        verify(refresh, never()).purgeChainsEndedBefore(any(), anyInt());
    }

    @Test
    @DisplayName("enabled: the scheduled entry point runs both tables")
    void enabledRunsBoth() {
        job(true, 90, 90, 1000, 500).purge();
        verify(qrs).purgeFinishedBefore(any(), eq(1000));
        verify(refresh).purgeChainsEndedBefore(any(), eq(1000));
    }

    @Test
    @DisplayName("non-positive settings fall back to the defaults — retention can never be configured to zero")
    void nonPositiveSettingsMeanTheDefault() {
        job(true, 0, -5, 0, -1).run(NOW);

        Instant cutoff = NOW.minus(TokenRetentionPurgeJob.DEFAULT_RETENTION_DAYS, ChronoUnit.DAYS);
        verify(qrs).purgeFinishedBefore(cutoff, TokenRetentionPurgeJob.DEFAULT_BATCH_SIZE);
        verify(refresh).purgeChainsEndedBefore(cutoff, TokenRetentionPurgeJob.DEFAULT_BATCH_SIZE);
    }

    @Test
    @DisplayName("replica-safe: the scheduled method carries a ShedLock with a sane lockAtMostFor")
    void scheduledMethodIsLocked() throws NoSuchMethodException {
        var method = TokenRetentionPurgeJob.class.getMethod("purge");
        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("tokenRetentionPurge");
        assertThat(Duration.parse(lock.lockAtMostFor())).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }
}

package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.exception.VoucherAttemptsLockedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two Lua scripts against a REAL Redis — Mockito cannot run Lua, so this is
 * the only test of what they actually do: the fifth miss locks, the window
 * slides, a lock is never extended, every key carries a TTL, and a lock that
 * somehow has none is repaired.
 *
 * <p>Runs a {@code redis:7-alpine} container (the cell runs Redis 7). Set
 * {@code LOYALTY_TEST_REDIS_PORT} to use an already-running Redis on localhost
 * instead — for a sandbox with {@code redis-server} but no Docker. Skipped when
 * neither is available.
 */
@EnabledIf("redisAvailable")
class VoucherGuessGuardRedisIT {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    static boolean redisAvailable() {
        return System.getenv("LOYALTY_TEST_REDIS_PORT") != null
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startRedis() {
        String host = "localhost";
        int port;
        String override = System.getenv("LOYALTY_TEST_REDIS_PORT");
        if (override != null) {
            port = Integer.parseInt(override);
        } else {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        }
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() {
        if (connections != null) {
            connections.destroy();
        }
        if (container != null) {
            container.stop();
        }
    }

    private SimpleMeterRegistry registry;

    @BeforeEach
    void freshRegistry() {
        registry = new SimpleMeterRegistry();
    }

    private VoucherGuessGuard guard(Duration window, Duration lock) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new VoucherGuessGuard(new VoucherGuardProperties(true, 5, window, lock, 1000),
                provider, new LoyaltyMetrics(registry));
    }

    /** A fresh identity per test, so tests never see each other's keys. */
    private static VoucherGuessGuard.Identity someone() {
        return new VoucherGuessGuard.Identity("phone", "+26377" + UUID.randomUUID());
    }

    @Test
    void theFifthMissLocks_forTheFullDuration() {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMinutes(30));
        var who = someone();
        for (int i = 0; i < 4; i++) {
            g.recordFailure(Optional.of(who));
        }
        assertThatCode(() -> g.checkNotLocked(Optional.of(who))).doesNotThrowAnyException();
        assertThat(redis.opsForZSet().zCard(VoucherGuessGuard.failuresKey(who))).isEqualTo(4);

        g.recordFailure(Optional.of(who));

        assertThatThrownBy(() -> g.checkNotLocked(Optional.of(who)))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1795L, 1800L));
        assertThat(redis.hasKey(VoucherGuessGuard.failuresKey(who)))
                .as("the log is cleared when the lock is set, so it restarts afterwards").isFalse();
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
        assertThat(registry.find("loyalty.voucher.guard.degraded").counter())
                .as("Redis answered every call").isNull();
    }

    @Test
    void everyKeyCarriesATtl() {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMinutes(30));
        var who = someone();
        g.recordFailure(Optional.of(who));
        assertThat(redis.getExpire(VoucherGuessGuard.failuresKey(who))).isBetween(1L, 60L);
        for (int i = 0; i < 4; i++) {
            g.recordFailure(Optional.of(who));
        }
        assertThat(redis.getExpire(VoucherGuessGuard.lockKey(who))).isBetween(1795L, 1800L);
    }

    @Test
    void missesWhileLocked_neverExtendTheLock_andAreNotLogged() throws InterruptedException {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofSeconds(30));
        var who = someone();
        for (int i = 0; i < 5; i++) {
            g.recordFailure(Optional.of(who));
        }
        long before = redis.getExpire(VoucherGuessGuard.lockKey(who), java.util.concurrent.TimeUnit.MILLISECONDS);
        Thread.sleep(300);
        for (int i = 0; i < 10; i++) {
            g.recordFailure(Optional.of(who));
        }
        long after = redis.getExpire(VoucherGuessGuard.lockKey(who), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(after).isLessThan(before);
        assertThat(redis.hasKey(VoucherGuessGuard.failuresKey(who))).isFalse();
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void theWindowSlides() throws InterruptedException {
        VoucherGuessGuard g = guard(Duration.ofMillis(400), Duration.ofMinutes(30));
        var who = someone();
        for (int i = 0; i < 4; i++) {
            g.recordFailure(Optional.of(who));
        }
        Thread.sleep(500);
        g.recordFailure(Optional.of(who));

        assertThatCode(() -> g.checkNotLocked(Optional.of(who))).doesNotThrowAnyException();
        assertThat(redis.opsForZSet().zCard(VoucherGuessGuard.failuresKey(who)))
                .as("the four old misses were pruned").isEqualTo(1);
    }

    @Test
    void theLockEnds() throws InterruptedException {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMillis(700));
        var who = someone();
        for (int i = 0; i < 5; i++) {
            g.recordFailure(Optional.of(who));
        }
        assertThatThrownBy(() -> g.checkNotLocked(Optional.of(who))).isInstanceOf(VoucherAttemptsLockedException.class);
        Thread.sleep(800);
        // The in-memory mirror of the Redis lock expires on the same schedule.
        assertThatCode(() -> g.checkNotLocked(Optional.of(who))).doesNotThrowAnyException();
    }

    @Test
    void aLockWithNoTtl_isRepairedByTheCheck_soItCanNeverBePermanent() {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMinutes(30));
        var who = someone();
        redis.opsForValue().set(VoucherGuessGuard.lockKey(who), "1");      // no TTL
        assertThat(redis.getExpire(VoucherGuessGuard.lockKey(who))).isEqualTo(-1L);

        assertThatThrownBy(() -> g.checkNotLocked(Optional.of(who))).isInstanceOf(VoucherAttemptsLockedException.class);
        assertThat(redis.getExpire(VoucherGuessGuard.lockKey(who))).isBetween(1795L, 1800L);
    }

    @Test
    void deletingTheLockInRedis_liftsIt() {
        // The operator's manual unlock: DEL the lock key.
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMinutes(30));
        var who = someone();
        for (int i = 0; i < 5; i++) {
            g.recordFailure(Optional.of(who));
        }
        assertThatThrownBy(() -> g.checkNotLocked(Optional.of(who))).isInstanceOf(VoucherAttemptsLockedException.class);

        redis.delete(VoucherGuessGuard.lockKey(who));

        assertThatCode(() -> g.checkNotLocked(Optional.of(who))).doesNotThrowAnyException();
    }

    @Test
    void identitiesDoNotShareCounters() {
        VoucherGuessGuard g = guard(Duration.ofMinutes(1), Duration.ofMinutes(30));
        var a = someone();
        var b = someone();
        for (int i = 0; i < 5; i++) {
            g.recordFailure(Optional.of(a));
        }
        assertThatCode(() -> g.checkNotLocked(Optional.of(b))).doesNotThrowAnyException();
    }
}

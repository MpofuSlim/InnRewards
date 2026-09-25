package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.exception.VoucherAttemptsLockedException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lockout's rules and its failure handling, without Redis (the in-memory
 * store) or with a Redis that misbehaves. The Lua scripts themselves are pinned
 * against a real Redis in {@code VoucherGuessGuardRedisIT}.
 */
class VoucherGuessGuardTest {

    private static final String PHONE = "+263771234567";

    private MutableClock clock;
    private SimpleMeterRegistry registry;
    private ObjectProvider<StringRedisTemplate> noRedis;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
        registry = new SimpleMeterRegistry();
        noRedis = providerOf(null);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private VoucherGuessGuard guard(ObjectProvider<StringRedisTemplate> redis) {
        return new VoucherGuessGuard(VoucherGuardProperties.defaults(), redis,
                new LoyaltyMetrics(registry), clock);
    }

    private static Optional<VoucherGuessGuard.Identity> phone(String p) {
        return Optional.of(new VoucherGuessGuard.Identity("phone", p));
    }

    // ------------------------------------------------------------ the rule

    @Test
    void fourMissesStillOpen_theFifthLocksForThirtyMinutes() {
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 4; i++) {
            g.recordFailure(who);
        }
        assertThatCode(() -> g.checkNotLocked(who)).doesNotThrowAnyException();

        g.recordFailure(who);

        assertThatThrownBy(() -> g.checkNotLocked(who))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1800));
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void theWindowSlides_missesOlderThanAMinuteDropOut() {
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 4; i++) {
            g.recordFailure(who);
        }
        clock.advance(Duration.ofSeconds(61));
        g.recordFailure(who);

        assertThatCode(() -> g.checkNotLocked(who)).doesNotThrowAnyException();
    }

    @Test
    void missesSpreadAcrossTheMinuteStillLock() {
        // A sliding log, not a fixed window: five misses 14s apart are five
        // within one minute however the minute is sliced.
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(who);
            clock.advance(Duration.ofSeconds(14));
        }
        assertThatThrownBy(() -> g.checkNotLocked(who)).isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    void attemptsWhileLocked_neverExtendIt_andRetryAfterCountsDown() {
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(who);
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 20; i++) {
            g.recordFailure(who);
        }
        assertThatThrownBy(() -> g.checkNotLocked(who))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(20 * 60));
    }

    @Test
    void theLockEnds_andTheCountStartsAgainFromZero() {
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(who);
        }
        clock.advance(Duration.ofMinutes(30));

        assertThatCode(() -> g.checkNotLocked(who)).doesNotThrowAnyException();
        g.recordFailure(who);
        assertThatCode(() -> g.checkNotLocked(who)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterRoundsUp_andIsNeverZero() {
        VoucherGuessGuard g = guard(noRedis);
        var who = phone(PHONE);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(who);
        }
        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertThatThrownBy(() -> g.checkNotLocked(who))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void oneCallersLock_neverTouchesAnother() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(phone(PHONE));
        }
        assertThatCode(() -> g.checkNotLocked(phone("+263771234568"))).doesNotThrowAnyException();
        assertThatCode(() -> g.checkNotLocked(Optional.of(new VoucherGuessGuard.Identity("staff", PHONE))))
                .as("same value, different kind — a different identity")
                .doesNotThrowAnyException();
    }

    @Test
    void disabled_checksAndCountsNothing() {
        VoucherGuessGuard g = new VoucherGuessGuard(
                new VoucherGuardProperties(false, null, null, null, null), noRedis,
                new LoyaltyMetrics(registry), clock);
        for (int i = 0; i < 10; i++) {
            g.recordFailure(phone(PHONE));
        }
        assertThatCode(() -> g.checkNotLocked(phone(PHONE))).doesNotThrowAnyException();
        assertThat(registry.find("loyalty.voucher.guard.failures").counter()).isNull();
    }

    @Test
    void aCallerWithNoIdentity_isCountedAsUnkeyed_andNeverLocked() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 10; i++) {
            g.recordFailure(Optional.empty());
        }
        assertThatCode(() -> g.checkNotLocked(Optional.empty())).doesNotThrowAnyException();
        assertThat(registry.counter("loyalty.voucher.guard.unkeyed").count()).isEqualTo(10);
    }

    // --------------------------------------------------- who is the caller

    @Test
    void aCustomer_isKeyedOnThePhoneClaim() {
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        assertThat(guard(noRedis).callerIdentity()).contains(new VoucherGuessGuard.Identity("phone", PHONE));
    }

    @Test
    void aCustomerWithNoPhoneClaim_fallsBackToTheUserId() {
        UUID id = UUID.randomUUID();
        signIn(new CallerDetails(null, null, null, id), "ROLE_CUSTOMER");
        assertThat(guard(noRedis).callerIdentity()).contains(new VoucherGuessGuard.Identity("user", id.toString()));
    }

    @Test
    void staff_areKeyedOnTheirAccount_evenWhenTheTokenCarriesAPhone() {
        UUID id = UUID.randomUUID();
        signIn(new CallerDetails(UUID.randomUUID(), UUID.randomUUID(), PHONE, id), "ROLE_SHOP_USER");
        assertThat(guard(noRedis).callerIdentity()).contains(new VoucherGuessGuard.Identity("staff", id.toString()));
    }

    @Test
    void aCustomerWhoIsAlsoStaff_isKeyedAsStaff() {
        UUID id = UUID.randomUUID();
        signIn(new CallerDetails(null, null, PHONE, id), "ROLE_CUSTOMER", "ROLE_SHOP_ADMIN");
        assertThat(guard(noRedis).callerIdentity()).contains(new VoucherGuessGuard.Identity("staff", id.toString()));
    }

    @Test
    void legacyStaffTokenWithNoUserId_isKeyedOnTheSubject() {
        signIn(new CallerDetails(null, null, null, null), "ROLE_MERCHANT_ADMIN");
        assertThat(guard(noRedis).callerIdentity())
                .contains(new VoucherGuessGuard.Identity("staff", "sub:cashier@example.com"));
    }

    @Test
    void noAuthentication_isNoIdentity() {
        assertThat(guard(noRedis).callerIdentity()).isEmpty();
    }

    // ------------------------------------------------------- Redis failure

    @Test
    void redisThrowing_fallsBackToMemory_andStillLocksAfterFive() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        VoucherGuessGuard g = guard(providerOf(redis));
        var who = phone(PHONE);

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> g.recordFailure(who)).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> g.checkNotLocked(who)).isInstanceOf(VoucherAttemptsLockedException.class);
        assertThat(registry.counter("loyalty.voucher.guard.degraded", "op", "record", "cause", "error").count())
                .isEqualTo(5);
        assertThat(registry.counter("loyalty.voucher.guard.degraded", "op", "check", "cause", "error").count())
                .isEqualTo(1);
    }

    @Test
    void aLockRedisReported_survivesRedisFailingMidLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // The gate reports a lock with 25 minutes left...
        when(redis.execute(eq(VoucherGuessGuard.GATE_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(25L * 60 * 1000)
                // ...then Redis goes away.
                .thenThrow(new RedisConnectionFailureException("down"));
        VoucherGuessGuard g = guard(providerOf(redis));
        var who = phone(PHONE);

        assertThatThrownBy(() -> g.checkNotLocked(who)).isInstanceOf(VoucherAttemptsLockedException.class);
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> g.checkNotLocked(who))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(20 * 60));
    }

    @Test
    void aMirroredLock_isIgnoredWhileRedisIsUp_soDeletingItInRedisLiftsIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.GATE_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(25L * 60 * 1000)
                .thenReturn(-2L);                     // an operator DEL'd the lock
        VoucherGuessGuard g = guard(providerOf(redis));
        var who = phone(PHONE);

        assertThatThrownBy(() -> g.checkNotLocked(who)).isInstanceOf(VoucherAttemptsLockedException.class);
        assertThatCode(() -> g.checkNotLocked(who)).doesNotThrowAnyException();
    }

    @Test
    void aLockTakenInMemoryDuringAnOutage_stillHoldsOnceRedisIsBack() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.RECORD_SCRIPT), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(redis.execute(eq(VoucherGuessGuard.GATE_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(-2L);                     // Redis back, and knows nothing
        VoucherGuessGuard g = guard(providerOf(redis));
        var who = phone(PHONE);
        for (int i = 0; i < 5; i++) {
            g.recordFailure(who);
        }
        assertThatThrownBy(() -> g.checkNotLocked(who)).isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRedisPath_passesTheConfiguredNumbers_andReportsANewLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Object[][] captured = new Object[1][];
        when(redis.execute(eq(VoucherGuessGuard.RECORD_SCRIPT), anyList(), any(Object[].class)))
                .thenAnswer(inv -> {
                    captured[0] = Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length);
                    assertThat((List<Object>) inv.getArgument(1)).containsExactly(
                            VoucherGuessGuard.failuresKey(new VoucherGuessGuard.Identity("phone", PHONE)),
                            VoucherGuessGuard.lockKey(new VoucherGuessGuard.Identity("phone", PHONE)));
                    return List.of(1L, 1_800_000L);
                });
        VoucherGuessGuard g = guard(providerOf(redis));

        g.recordFailure(phone(PHONE));

        assertThat(captured[0]).hasSize(4);
        assertThat(captured[0][0]).isEqualTo("60000");
        assertThat(captured[0][1]).isEqualTo("1800000");
        assertThat(captured[0][2]).isEqualTo("5");
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void redisKeys_neverContainThePhoneNumber() {
        var who = new VoucherGuessGuard.Identity("phone", PHONE);
        assertThat(VoucherGuessGuard.lockKey(who)).doesNotContain("263771234567")
                .matches("loyalty:voucher-guard:\\{[0-9a-f]{32}}:lock");
        assertThat(VoucherGuessGuard.failuresKey(who)).doesNotContain("263771234567");
        // sha256("phone:+263771234567"), first 32 hex chars — the operator's recipe.
        assertThat(VoucherGuessGuard.ref(who)).hasSize(32);
    }

    // --------------------------------------------------------------- helpers

    private static void signIn(CallerDetails details, String... roles) {
        var auth = new UsernamePasswordAuthenticationToken("cashier@example.com", null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(redis);
        return p;
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

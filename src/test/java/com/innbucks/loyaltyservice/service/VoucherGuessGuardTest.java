package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.VoucherAttemptsLockedException;
import com.innbucks.loyaltyservice.exception.VoucherCodeGuessException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final VoucherGuessGuard.Identity WHO = new VoucherGuessGuard.Identity("phone", PHONE);

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

    /** One attempt that turns out to be a guess. */
    private static void missOnce(VoucherGuessGuard g, VoucherGuessGuard.Identity who) {
        g.miss(who, g.admit(who));
    }

    /** An attempt that is not a guess — admitted, then given back. */
    private static void tryOnce(VoucherGuessGuard g, VoucherGuessGuard.Identity who) {
        g.release(who, g.admit(who));
    }

    // ------------------------------------------------------------ the rule

    @Test
    void fourMissesStillOpen_theFifthLocksForThirtyMinutes() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 4; i++) {
            missOnce(g, WHO);
        }
        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();

        missOnce(g, WHO);

        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1800));
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void theWindowSlides_missesOlderThanAMinuteDropOut() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 4; i++) {
            missOnce(g, WHO);
        }
        clock.advance(Duration.ofSeconds(61));
        missOnce(g, WHO);

        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
    }

    @Test
    void missesSpreadAcrossTheMinuteStillLock() {
        // A sliding log, not a fixed window: five misses 14s apart are five
        // within one minute however the minute is sliced.
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
            clock.advance(Duration.ofSeconds(14));
        }
        assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    void attemptsWhileLocked_areRefused_andNeverExtendIt() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
        }
        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(20 * 60));
    }

    @Test
    void aMissSettledAfterTheLockTripped_isNotLogged_andDoesNotExtendIt() {
        // A reservation taken before the lock (on another replica, or in flight
        // when the fifth miss landed) settles as a miss once the caller is
        // locked: it must neither count nor push the unlock time out.
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        clock.advance(Duration.ofMinutes(29));
        g.miss(WHO, "reserved-before-the-lock");

        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void theLockEnds_andTheCountStartsAgainFromZero() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        clock.advance(Duration.ofMinutes(30));

        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
        missOnce(g, WHO);
        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterRoundsUp_andIsNeverZero() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void oneCallersLock_neverTouchesAnother() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        assertThatCode(() -> tryOnce(g, new VoucherGuessGuard.Identity("phone", "+263771234568")))
                .doesNotThrowAnyException();
        assertThatCode(() -> tryOnce(g, new VoucherGuessGuard.Identity("staff", PHONE)))
                .as("same value, different kind — a different identity")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------ reservations (bursts)

    @Test
    void attemptsInFlight_useTheBudget_soABurstCannotOutrunTheCount() {
        VoucherGuessGuard g = guard(noRedis);
        List<String> inFlight = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inFlight.add(g.admit(WHO));
        }
        // A sixth concurrent attempt is refused before it can look anything up
        // — but only until a slot frees, not for the 30-minute lock.
        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));

        g.release(WHO, inFlight.remove(0));                   // one was not a guess
        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
    }

    @Test
    void attemptsThatWereNotGuesses_giveTheirSlotBack() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 50; i++) {
            tryOnce(g, WHO);
        }
        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
    }

    @Test
    void aStuckReservation_agesOutWithTheWindow() {
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            g.admit(WHO);                                     // never settled
        }
        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> tryOnce(g, WHO)).doesNotThrowAnyException();
    }

    @RepeatedTest(20)
    void aRealBurstOfParallelGuesses_getsExactlyFiveLookups() throws Exception {
        // 40 threads, one identity, every call a guess, all released at once —
        // the shape that let every one of them through before reservations.
        // Repeated because the race it pins is intermittent: reading the lock
        // outside the per-identity compute let up to 11 through, about one run
        // in seven.
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        SecurityContext context = SecurityContextHolder.getContext();
        VoucherGuessGuard g = guard(noRedis);
        int threads = 40;
        CountDownLatch started = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    SecurityContextHolder.setContext(context);
                    started.countDown();
                    try {
                        go.await();
                        g.attempt(() -> {
                            lookups.incrementAndGet();
                            throw VoucherCodeGuessException.unknownCode();
                        });
                    } catch (VoucherAttemptsLockedException locked) {
                        refused.incrementAndGet();
                    } catch (VoucherCodeGuessException | InterruptedException expected) {
                        // a counted miss
                    }
                    return null;
                }));
            }
            started.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(lookups.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(threads - 5);
        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1800));
    }

    // ------------------------------------------------------ attempt(...)

    @Test
    void attempt_countsAGuessException_andRethrowsIt() {
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> g.attempt(() -> {
                throw VoucherCodeGuessException.unknownCode();
            })).isInstanceOf(VoucherCodeGuessException.class);
        }
        assertThatThrownBy(() -> g.attempt(() -> "never runs"))
                .isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    void attempt_otherRefusalsAndSuccesses_giveTheSlotBack() {
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> g.attempt(() -> {
                throw LoyaltyException.conflict("ALREADY_REDEEMED", "spent");
            })).isInstanceOf(LoyaltyException.class);
            assertThat(g.attempt(() -> "ok")).isEqualTo("ok");
        }
        assertThat(g.attempt(() -> "still open")).isEqualTo("still open");
    }

    @Test
    void attempt_aResultTheCallerNamesAMiss_counts() {
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        VoucherGuessGuard g = guard(noRedis);
        for (int i = 0; i < 5; i++) {
            assertThat(g.attempt(() -> false, found -> !found)).isFalse();
        }
        assertThatThrownBy(() -> g.attempt(() -> true, found -> !found))
                .isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    void attempt_disabled_runsTheBodyAndCountsNothing() {
        signIn(new CallerDetails(null, null, PHONE, UUID.randomUUID()), "ROLE_CUSTOMER");
        VoucherGuessGuard g = new VoucherGuessGuard(
                new VoucherGuardProperties(false, null, null, null, null), noRedis,
                new LoyaltyMetrics(registry), clock);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> g.attempt(() -> {
                throw VoucherCodeGuessException.unknownCode();
            })).isInstanceOf(VoucherCodeGuessException.class);
        }
        assertThat(g.attempt(() -> "ok")).isEqualTo("ok");
        assertThat(registry.find("loyalty.voucher.guard.failures").counter()).isNull();
    }

    @Test
    void attempt_aCallerWithNoIdentity_isCountedAsUnkeyed_andNeverLocked() {
        VoucherGuessGuard g = guard(noRedis);                  // no authentication at all
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> g.attempt(() -> {
                throw VoucherCodeGuessException.unknownCode();
            })).isInstanceOf(VoucherCodeGuessException.class);
        }
        assertThat(g.attempt(() -> "ok")).isEqualTo("ok");
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

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> missOnce(g, WHO)).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
        assertThat(registry.counter("loyalty.voucher.guard.degraded", "op", "miss", "cause", "error").count())
                .isEqualTo(5);
        // Five, not six: once the in-memory store holds its OWN lock, the sixth
        // admit is refused from it before Redis is asked at all.
        assertThat(registry.counter("loyalty.voucher.guard.degraded", "op", "admit", "cause", "error").count())
                .isEqualTo(5);
    }

    @Test
    void aLockRedisReported_survivesRedisFailingMidLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.ADMIT_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 25L * 60 * 1000))        // locked, 25 minutes left
                .thenThrow(new RedisConnectionFailureException("down"));
        VoucherGuessGuard g = guard(providerOf(redis));

        assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(20 * 60));
    }

    @Test
    void aMirroredLock_isIgnoredWhileRedisIsUp_soDeletingItInRedisLiftsIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.ADMIT_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 25L * 60 * 1000))
                .thenReturn(List.of(0L, 0L));                    // an operator DEL'd the lock
        VoucherGuessGuard g = guard(providerOf(redis));

        assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
        assertThatCode(() -> g.admit(WHO)).doesNotThrowAnyException();
    }

    @Test
    void aLockTakenInMemoryDuringAnOutage_stillHoldsOnceRedisIsBack() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.MISS_SCRIPT), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(redis.execute(eq(VoucherGuessGuard.ADMIT_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 0L));                    // Redis admits, knowing nothing
        VoucherGuessGuard g = guard(providerOf(redis));
        for (int i = 0; i < 5; i++) {
            missOnce(g, WHO);
        }
        assertThatThrownBy(() -> g.admit(WHO)).isInstanceOf(VoucherAttemptsLockedException.class);
    }

    @Test
    void aBusyAnswerFromRedis_isA429ButNotMirroredAsALock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(VoucherGuessGuard.ADMIT_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(List.of(2L, 12_000L))
                .thenThrow(new RedisConnectionFailureException("down"));
        VoucherGuessGuard g = guard(providerOf(redis));

        assertThatThrownBy(() -> g.admit(WHO))
                .isInstanceOfSatisfying(VoucherAttemptsLockedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(12));
        assertThatCode(() -> g.admit(WHO)).as("no lock was mirrored for a busy answer")
                .doesNotThrowAnyException();
    }

    @Test
    void theRedisPath_passesTheConfiguredNumbers_andReportsANewLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Object[][] captured = new Object[1][];
        when(redis.execute(eq(VoucherGuessGuard.ADMIT_SCRIPT), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 0L));
        when(redis.execute(eq(VoucherGuessGuard.MISS_SCRIPT), anyList(), any(Object[].class)))
                .thenAnswer(inv -> {
                    captured[0] = Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length);
                    return List.of(1L, 1_800_000L);
                });
        VoucherGuessGuard g = guard(providerOf(redis));

        String reservation = g.admit(WHO);
        g.miss(WHO, reservation);

        assertThat(captured[0][0]).isEqualTo(List.of(VoucherGuessGuard.failuresKey(WHO),
                VoucherGuessGuard.inFlightKey(WHO), VoucherGuessGuard.lockKey(WHO)));
        assertThat(Arrays.copyOfRange(captured[0], 1, captured[0].length))
                .containsExactly("60000", "1800000", "5", reservation);
        assertThat(registry.counter("loyalty.voucher.guard.locked", "kind", "phone").count()).isEqualTo(1);
    }

    @Test
    void redisKeys_neverContainThePhoneNumber() {
        assertThat(VoucherGuessGuard.lockKey(WHO)).doesNotContain("263771234567")
                .matches("loyalty:voucher-guard:\\{[0-9a-f]{32}}:lock");
        assertThat(VoucherGuessGuard.failuresKey(WHO)).doesNotContain("263771234567");
        assertThat(VoucherGuessGuard.inFlightKey(WHO)).doesNotContain("263771234567");
        assertThat(VoucherGuessGuard.ref(WHO)).hasSize(32);
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
        private volatile Instant now;

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
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

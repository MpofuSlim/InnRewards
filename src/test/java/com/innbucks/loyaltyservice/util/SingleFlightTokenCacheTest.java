package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the single-flight token rules every platform client relies on: a valid
 * token never waits behind a login, N callers cost one login, a rejected token
 * is refreshed once, a hung login is bounded, and a failed one is retried.
 */
class SingleFlightTokenCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MARGIN = Duration.ofSeconds(30);

    /** The client's own transient exception, as the clients supply it. */
    static final class Transient extends RuntimeException {
        Transient(String m) {
            super(m);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(32);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static SingleFlightTokenCache.Token token(String value, Duration validFor) {
        return new SingleFlightTokenCache.Token(value, NOW.plus(validFor));
    }

    private static SingleFlightTokenCache cache(SingleFlightTokenCache.Login login, Duration maxWait) {
        return new SingleFlightTokenCache(login, MARGIN, maxWait,
                () -> new Transient("timed out waiting for login"), CLOCK);
    }

    @Test
    @DisplayName("a fresh token is returned without any login")
    void freshToken_noLogin() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            logins.incrementAndGet();
            return token("t1", Duration.ofMinutes(10));
        }, Duration.ofSeconds(5));

        assertThat(c.get()).isEqualTo("t1");
        assertThat(c.get()).isEqualTo("t1");
        assertThat(c.get()).isEqualTo("t1");
        assertThat(logins).hasValue(1);
    }

    @Test
    @DisplayName("callers holding a still-valid token never wait while the refresh is blocked")
    void validTokenCallers_neverWaitDuringABlockedRefresh() throws Exception {
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            int n = logins.incrementAndGet();
            if (n == 1) {
                // Inside the 30s margin but not expired: stale-while-refreshing.
                return token("stale", Duration.ofSeconds(10));
            }
            loginEntered.countDown();
            try {
                releaseLogin.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return token("fresh", Duration.ofMinutes(10));
        }, Duration.ofSeconds(30));

        assertThat(c.get()).isEqualTo("stale");
        Future<String> owner = pool.submit(c::get);
        assertThat(loginEntered.await(5, TimeUnit.SECONDS)).isTrue();

        List<Future<String>> others = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            others.add(pool.submit(c::get));
        }
        for (Future<String> f : others) {
            // Each returns at once with the still-valid token; the login is
            // still blocked, so a waiting caller would time out here.
            assertThat(f.get(1, TimeUnit.SECONDS)).isEqualTo("stale");
        }

        releaseLogin.countDown();
        assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("fresh");
        assertThat(c.get()).isEqualTo("fresh");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("N cold callers cause exactly one login")
    void coldCallers_oneLogin() throws Exception {
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            logins.incrementAndGet();
            try {
                releaseLogin.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return token("t1", Duration.ofMinutes(10));
        }, Duration.ofSeconds(10));

        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            callers.add(pool.submit(c::get));
        }
        Thread.sleep(200); // let them all reach the in-flight login
        releaseLogin.countDown();
        for (Future<String> f : callers) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("t1");
        }
        assertThat(logins).hasValue(1);
    }

    @Test
    @DisplayName("N concurrent refreshes after the same rejection cause exactly one login")
    void concurrentForce_oneLogin() throws Exception {
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            int n = logins.incrementAndGet();
            if (n == 1) {
                return token("rejected", Duration.ofMinutes(10));
            }
            try {
                releaseLogin.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return token("t2", Duration.ofMinutes(10));
        }, Duration.ofSeconds(10));
        assertThat(c.get()).isEqualTo("rejected");

        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            callers.add(pool.submit(() -> c.refreshAfterRejection("rejected")));
        }
        Thread.sleep(200);
        releaseLogin.countDown();
        for (Future<String> f : callers) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("t2");
        }
        // A late caller still holding the old token gets the new one, no login.
        assertThat(c.refreshAfterRejection("rejected")).isEqualTo("t2");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("a rejected token is never handed out again while its replacement is logging in")
    void rejectedToken_isDroppedFromTheCache() throws Exception {
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                return token("rejected", Duration.ofMinutes(10));
            }
            loginEntered.countDown();
            try {
                releaseLogin.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return token("t2", Duration.ofMinutes(10));
        }, Duration.ofSeconds(10));
        c.get();

        Future<String> forcer = pool.submit(() -> c.refreshAfterRejection("rejected"));
        assertThat(loginEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> plain = pool.submit(c::get);
        Thread.sleep(100);
        assertThat(plain).isNotDone();

        releaseLogin.countDown();
        assertThat(forcer.get(5, TimeUnit.SECONDS)).isEqualTo("t2");
        assertThat(plain.get(5, TimeUnit.SECONDS)).isEqualTo("t2");
    }

    @Test
    @DisplayName("a cold caller joined to a hung login gets the transient exception within the bound")
    void hungLogin_coldJoinerTimesOut() throws Exception {
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        SingleFlightTokenCache c = cache(() -> {
            loginEntered.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new Transient("login interrupted");
        }, Duration.ofMillis(300));

        pool.submit(c::get); // the owner, stuck in the login
        assertThat(loginEntered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(c::get)
                .isInstanceOf(Transient.class)
                .hasMessage("timed out waiting for login");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(tookMs).isBetween(250L, 3_000L);
        never.countDown();
    }

    @Test
    @DisplayName("a failed login is not cached: joiners see the failure and the next caller retries")
    void failedLogin_isRetriedByTheNextCaller() throws Exception {
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                loginEntered.countDown();
                try {
                    releaseLogin.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new Transient("login refused");
            }
            return token("t2", Duration.ofMinutes(10));
        }, Duration.ofSeconds(10));

        Future<String> owner = pool.submit(c::get);
        assertThat(loginEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> joiner = pool.submit(c::get);
        Thread.sleep(100);
        releaseLogin.countDown();

        for (Future<String> f : List.of(owner, joiner)) {
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(Transient.class)
                    .hasRootCauseMessage("login refused");
        }
        assertThat(c.get()).isEqualTo("t2");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("a failed refresh keeps serving the not-yet-expired token to the caller that ran it")
    void failedRefresh_withAValidToken_fallsBack() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache c = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                return token("stale", Duration.ofSeconds(10));
            }
            throw new Transient("login refused");
        }, Duration.ofSeconds(10));

        assertThat(c.get()).isEqualTo("stale");
        assertThat(c.get()).isEqualTo("stale");
        assertThat(c.get()).isEqualTo("stale");
        // Every call inside the margin retries the refresh; none is stuck.
        assertThat(logins).hasValue(3);
    }

    @Test
    @DisplayName("the token value never appears in toString")
    void tokenToString_hidesTheValue() {
        assertThat(token("secret-bearer", Duration.ofMinutes(1)).toString())
                .doesNotContain("secret-bearer");
    }
}

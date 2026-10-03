package com.innbucks.loyaltyservice.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A cached upstream bearer token whose refresh never holds a lock across the
 * network call. Shared by every client that logs in to the InnBucks platform
 * ({@code POST /auth/third-party}) before calling it.
 *
 * <p>The clients used to guard their token with {@code synchronized
 * currentToken(...)}, which held the monitor for the whole login. A slow login
 * (about 19 s measured) therefore blocked every sender behind it, including the
 * ones holding a perfectly good token. The rules here, in order:
 * <ol>
 *   <li><b>Fast path, no lock.</b> A token valid beyond the refresh margin is
 *       read from an {@link AtomicReference} and returned.</li>
 *   <li><b>Single flight.</b> At most one login runs per cache. The lock is held
 *       only to start or join that login's {@link CompletableFuture}, never
 *       while it runs.</li>
 *   <li><b>Stale while refreshing.</b> A token inside the margin but not yet
 *       expired is returned at once to every caller that did not start the
 *       refresh.</li>
 *   <li><b>Bounded wait.</b> A caller with no usable token waits for the
 *       in-flight login for at most {@code maxWait} (the client's connect +
 *       read timeout plus a small margin), then gets the client's own
 *       transient exception, the one it already throws when the upstream is
 *       down.</li>
 *   <li><b>One login per rejection.</b> {@link #refreshAfterRejection} logs in
 *       again only when the cached token is still the one the upstream refused;
 *       otherwise it returns the newer token. N concurrent 401s cost one login.</li>
 *   <li><b>No poisoned cache.</b> A failed login completes the future
 *       exceptionally, frees the slot so the next caller retries, and caches
 *       nothing. A rejected token is dropped from the cache.</li>
 * </ol>
 *
 * <p>The login runs on the thread of the caller that starts it, so its own
 * wait is bounded by the client's HTTP timeouts. This class never logs; the
 * token value never leaves it except as the return value.
 */
public final class SingleFlightTokenCache {

    /** A token and the instant the upstream stops accepting it. */
    public record Token(String value, Instant expiresAt) {
        public Token {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public String toString() {
            return "Token[expiresAt=" + expiresAt + "]";
        }
    }

    /** Performs one login. Throws the client's own exception on failure. */
    @FunctionalInterface
    public interface Login {
        Token login();
    }

    private final Login login;
    private final Duration refreshMargin;
    private final Duration maxWait;
    private final Supplier<? extends RuntimeException> waitTimedOut;
    private final Clock clock;

    private final AtomicReference<Token> current = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}. Null when no login is running. */
    private CompletableFuture<Token> inFlight;

    /**
     * @param login         the upstream login
     * @param refreshMargin how long before expiry a refresh starts
     * @param maxWait       the longest a caller with no usable token waits for
     *                      someone else's login
     * @param waitTimedOut  the exception thrown when that wait runs out; must be
     *                      the client's existing transient/unavailable type
     */
    public SingleFlightTokenCache(Login login, Duration refreshMargin, Duration maxWait,
                                  Supplier<? extends RuntimeException> waitTimedOut) {
        this(login, refreshMargin, maxWait, waitTimedOut, Clock.systemUTC());
    }

    public SingleFlightTokenCache(Login login, Duration refreshMargin, Duration maxWait,
                                  Supplier<? extends RuntimeException> waitTimedOut, Clock clock) {
        this.login = Objects.requireNonNull(login, "login");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        this.waitTimedOut = Objects.requireNonNull(waitTimedOut, "waitTimedOut");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The current token, refreshing it first when it is missing or near expiry. */
    public String get() {
        Token cached = current.get();
        Instant now = clock.instant();
        if (isFresh(cached, now)) {
            return cached.value();
        }
        Token usable = isUsable(cached, now) ? cached : null;
        Flight flight;
        lock.lock();
        try {
            flight = startOrJoin();
        } finally {
            lock.unlock();
        }
        if (flight.owner()) {
            return runLogin(flight.future(), usable);
        }
        if (usable != null) {
            return usable.value();
        }
        return await(flight.future());
    }

    /**
     * Called after the upstream refused {@code rejected} (401/403). Logs in
     * again only if that token is still the cached one; a newer token, or a
     * login already running, is reused.
     */
    public String refreshAfterRejection(String rejected) {
        Flight flight;
        lock.lock();
        try {
            Token cached = current.get();
            if (cached != null && !cached.value().equals(rejected)
                    && isUsable(cached, clock.instant())) {
                return cached.value();
            }
            if (cached != null && cached.value().equals(rejected)) {
                // The upstream refused it: no caller may be handed it again.
                current.compareAndSet(cached, null);
            }
            flight = startOrJoin();
        } finally {
            lock.unlock();
        }
        return flight.owner() ? runLogin(flight.future(), null) : await(flight.future());
    }

    /** Must be called with {@link #lock} held. */
    private Flight startOrJoin() {
        if (inFlight != null) {
            return new Flight(inFlight, false);
        }
        inFlight = new CompletableFuture<>();
        return new Flight(inFlight, true);
    }

    private String runLogin(CompletableFuture<Token> future, Token fallback) {
        Token fresh;
        try {
            fresh = Objects.requireNonNull(login.login(), "login returned no token");
        } catch (Throwable failure) {
            finish(future, null);
            future.completeExceptionally(failure);
            if (fallback != null && isUsable(fallback, clock.instant())) {
                // The refresh failed but the token we hold has not expired:
                // keep using it. The next caller inside the margin tries again.
                return fallback.value();
            }
            throw failure;
        }
        finish(future, fresh);
        future.complete(fresh);
        return fresh.value();
    }

    /** Publishes the result (if any) and frees the slot, in that order. */
    private void finish(CompletableFuture<Token> future, Token fresh) {
        lock.lock();
        try {
            if (fresh != null) {
                current.set(fresh);
            }
            if (inFlight == future) {
                inFlight = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private String await(CompletableFuture<Token> future) {
        try {
            return future.get(maxWait.toMillis(), TimeUnit.MILLISECONDS).value();
        } catch (TimeoutException e) {
            throw waitTimedOut.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw waitTimedOut.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw waitTimedOut.get();
        }
    }

    private boolean isFresh(Token t, Instant now) {
        return t != null && now.isBefore(t.expiresAt().minus(refreshMargin));
    }

    private static boolean isUsable(Token t, Instant now) {
        return t != null && now.isBefore(t.expiresAt());
    }

    private record Flight(CompletableFuture<Token> future, boolean owner) {
    }
}

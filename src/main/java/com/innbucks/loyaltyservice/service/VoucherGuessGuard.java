package com.innbucks.loyaltyservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.exception.VoucherAttemptsLockedException;
import com.innbucks.loyaltyservice.exception.VoucherCodeGuessException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The voucher-guessing lockout. Owner decision (2026-09-25): <b>5 unknown codes
 * within a minute locks that caller out of voucher redemption for 30 minutes</b>
 * (all three numbers are {@link VoucherGuardProperties}).
 *
 * <h2>Who is locked — the caller's own identity, from the token</h2>
 * <ul>
 *   <li><b>Staff</b> (SUPER_ADMIN / MERCHANT_ADMIN / SHOP_ADMIN / SHOP_USER) —
 *   keyed on their ACCOUNT ({@code userUuid}, else the token subject). One
 *   cashier locking themselves out does not lock the other tills at the shop.
 *   A staff token may carry a phone claim; it is still keyed on the account,
 *   and a CUSTOMER+staff account is treated as staff, as {@code doRedeem}
 *   does.</li>
 *   <li><b>Customer</b> — keyed on the {@code phoneNumber} claim, else the
 *   {@code userUuid}.</li>
 * </ul>
 * Never from the request body ({@code userId}, {@code ipAddress},
 * {@code deviceFingerprint} are all caller-chosen, so keying on them would let
 * an attacker rotate past the lock or aim it at someone else). Not scoped by
 * tenant either: {@code X-Tenant-Id} is caller-chosen too, and scoping by it
 * would multiply an attacker's budget by the number of tenants.
 *
 * <p><b>No IP key</b> (owner decision). Loyalty cannot see a client's address:
 * the gateway rewrites it, so every request arrives from the gateway pod and an
 * IP key would be ONE key for the whole cell. The unauthenticated
 * {@code /loyalty/public/**} redeem is deliberately not locked at all — it has
 * no identity to key on, it is staging-only, and
 * {@code loyalty.public-test.enabled=false} is its production control.
 *
 * <h2>What counts</h2>
 * A {@link VoucherCodeGuessException} from the guarded call, or a result the
 * caller's predicate names a miss (mark-viewed's unknown code). A mistyped code
 * (caught by the check digit), a double tap on a spent voucher, a voucher at the
 * wrong shop, an expired one — none of those are guesses. A successful
 * redemption does NOT clear the count (owner decision): otherwise a guesser
 * holding one real voucher could reset their budget with it.
 *
 * <h2>Attempts are RESERVED before the lookup, not counted after it</h2>
 * {@link #attempt} takes a slot in the caller's budget BEFORE the guarded call
 * runs, turns it into a counted miss if the call was a guess, and gives it back
 * otherwise. Checking first and counting afterwards is not enough: a caller
 * firing a burst of parallel requests would have every one pass the check before
 * the fifth miss landed, and guess as many codes as the gateway lets through at
 * once rather than five. With a reservation, misses plus attempts still in
 * flight can never exceed the budget; the one past it is refused with a 429
 * whose {@code Retry-After} is when the oldest slot frees up (at most the
 * window) — not the 30-minute lock, which only counted misses ever set.
 *
 * <h2>Storage — Redis first, memory as the fallback; never fail open, never 503</h2>
 * Per identity Redis holds a sliding log of misses, a sliding set of in-flight
 * reservations and the lock, each change made by ONE atomic script. If Redis is
 * absent or throws, an in-memory store applies the same rules, so an outage
 * neither removes the limit (fail-open) nor refuses every redemption
 * (fail-closed 503 on the spend path). Locks Redis reports are mirrored
 * locally, so a Redis blip in the middle of a lock does not end it. With one
 * replica the fallback is exactly as strict as Redis; with N it loosens to N×
 * until Redis is back, and a restart clears it.
 *
 * <p><b>Nothing escapes this class except {@link VoucherAttemptsLockedException}
 * and whatever the guarded call itself throws.</b> It runs on the redemption
 * path; a monitoring fault must never become a failed redemption.
 *
 * <p>Redis keys carry a SHA-256 prefix of the identity, never the phone number
 * itself: {@code loyalty:voucher-guard:{<ref>}:lock}. To lift a lock by hand
 * (runbook), compute the ref the same way ({@link #ref}) and {@code DEL} the
 * {@code :lock} and {@code :fails} keys; a lock taken while Redis was down lives
 * in one replica's memory and ends with its TTL or a restart.
 */
@Component
public class VoucherGuessGuard {

    private static final Logger log = LoggerFactory.getLogger(VoucherGuessGuard.class);

    private static final String KEY_PREFIX = "loyalty:voucher-guard:";

    /** The four roles that redeem at a till on someone else's behalf. */
    private static final String[] STAFF_ROLES = {
            "ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER"};

    static final long ADMITTED = 0;
    static final long LOCKED = 1;
    static final long BUSY = 2;

    /**
     * Reserve one attempt. KEYS: misses (ZSET), in-flight (ZSET), lock.
     * ARGV: window ms, lock ms, max, member. Returns {state, ms}: ADMITTED 0,
     * LOCKED with the lock's remaining ms, or BUSY with the ms until the oldest
     * slot leaves the window.
     *
     * <ul>
     *   <li>Time is Redis's own ({@code TIME}), so replicas with skewed clocks
     *   share one window.</li>
     *   <li>A lock with no TTL cannot be set by these scripts, but if one ever
     *   exists it is repaired here: a locked caller never gets further than
     *   this script, so this is the only place that could stop it being
     *   permanent.</li>
     * </ul>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List> ADMIT_SCRIPT = new DefaultRedisScript<>("""
            local tm = redis.call('TIME')
            local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
            local win, lockms, max = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            local ttl = redis.call('PTTL', KEYS[3])
            if ttl == -1 then
              redis.call('PEXPIRE', KEYS[3], lockms)
              return {1, lockms}
            end
            if ttl > 0 then
              return {1, ttl}
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - win)
            if redis.call('ZCARD', KEYS[1]) + redis.call('ZCARD', KEYS[2]) >= max then
              local oldest = now
              local a = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              if a[2] then oldest = math.min(oldest, tonumber(a[2])) end
              local b = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
              if b[2] then oldest = math.min(oldest, tonumber(b[2])) end
              return {2, math.max(1, oldest + win - now)}
            end
            redis.call('ZADD', KEYS[2], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[2], win)
            return {0, 0}
            """, List.class);

    /**
     * Turn a reservation into a counted miss and trip the lock at the threshold.
     * Same KEYS and ARGV as {@link #ADMIT_SCRIPT}. Returns {newlyLocked (1|0),
     * lock ms remaining (0 = not locked)}. A miss while already locked is not
     * logged and does NOT extend the lock, so {@code Retry-After} is truthful and
     * every lock ends. Both logs always carry a TTL and never exceed {@code max}
     * entries between them.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List> MISS_SCRIPT = new DefaultRedisScript<>("""
            local tm = redis.call('TIME')
            local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
            local win, lockms, max = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            redis.call('ZREM', KEYS[2], ARGV[4])
            local ttl = redis.call('PTTL', KEYS[3])
            if ttl == -1 then
              redis.call('PEXPIRE', KEYS[3], lockms)
              return {0, lockms}
            end
            if ttl > 0 then
              return {0, ttl}
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[1], win)
            if redis.call('ZCARD', KEYS[1]) >= max then
              redis.call('SET', KEYS[3], '1', 'PX', lockms)
              redis.call('DEL', KEYS[1])
              return {1, lockms}
            end
            return {0, 0}
            """, List.class);

    /** The identity a caller is locked under. {@code kind} is phone | staff | user. */
    public record Identity(String kind, String value) {
        String id() {
            return kind + ":" + value;
        }

        /** For logs: the phone masked, anything else only by its ref. */
        String logSafe() {
            return "phone".equals(kind) ? "phone " + MsisdnMasking.mask(value) + " ref " + ref(this)
                    : kind + " ref " + ref(this);
        }
    }

    private final VoucherGuardProperties props;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final LoyaltyMetrics metrics;
    private final Clock clock;
    private final FallbackStore fallback;

    @Autowired
    public VoucherGuessGuard(VoucherGuardProperties props,
                             ObjectProvider<StringRedisTemplate> redisProvider,
                             LoyaltyMetrics metrics) {
        this(props, redisProvider, metrics, Clock.systemUTC());
    }

    /** With an explicit clock for the in-memory store — tests drive time through it. */
    public VoucherGuessGuard(VoucherGuardProperties props,
                             ObjectProvider<StringRedisTemplate> redisProvider,
                             LoyaltyMetrics metrics,
                             Clock clock) {
        this.props = props;
        this.redisProvider = redisProvider;
        this.metrics = metrics;
        this.clock = clock;
        this.fallback = new FallbackStore(props);
    }

    /** True for a caller who presents codes at a till on someone else's behalf. */
    public static boolean isStaffCaller() {
        return CallerDetails.hasAnyRole(STAFF_ROLES);
    }

    /**
     * The authenticated caller's identity, read from the security context only.
     * Empty for a caller with none — which no role allowed to redeem should be,
     * so it is counted ({@code loyalty.voucher.guard.unkeyed}) rather than guessed.
     */
    public Optional<Identity> callerIdentity() {
        if (isStaffCaller()) {
            UUID userId = CallerDetails.currentUserId();
            if (userId != null) {
                return Optional.of(new Identity("staff", userId.toString()));
            }
            String subject = CallerDetails.currentEmail();
            return notBlank(subject) ? Optional.of(new Identity("staff", "sub:" + subject.strip()))
                    : Optional.empty();
        }
        if (CallerDetails.hasAnyRole("ROLE_CUSTOMER")) {
            String phone = CallerDetails.currentPhoneNumber();
            if (notBlank(phone)) {
                return Optional.of(new Identity("phone", phone.strip()));
            }
            UUID userId = CallerDetails.currentUserId();
            if (userId != null) {
                return Optional.of(new Identity("user", userId.toString()));
            }
        }
        return Optional.empty();
    }

    /**
     * Runs one voucher-code attempt for the authenticated caller under the
     * lockout. A {@link VoucherCodeGuessException} from {@code body} is a
     * counted miss.
     *
     * @throws VoucherAttemptsLockedException when the caller is locked, or has
     *         as many attempts in flight as they have misses left
     */
    public <T> T attempt(Supplier<T> body) {
        return attempt(body, result -> false);
    }

    /**
     * {@link #attempt(Supplier)}, where a RESULT can also be a miss — for an
     * endpoint that answers an unknown code without throwing.
     *
     * <p>The slot is reserved FIRST, so a locked caller learns nothing and costs
     * no database connection or row lock, and a correct code is refused too
     * while the lock lasts — that is what makes it stop guessing. It is settled
     * AFTER {@code body} returns: for a {@code @Transactional} body the
     * transaction has already rolled back and released its row lock, so a slow
     * Redis never lengthens one.
     */
    public <T> T attempt(Supplier<T> body, Predicate<? super T> countsAsMiss) {
        if (!props.enabled()) {
            return body.get();
        }
        Identity who = callerIdentity().orElse(null);
        if (who == null) {
            return unkeyed(body, countsAsMiss);
        }
        String reservation = admit(who);
        boolean settled = false;
        try {
            T result = body.get();
            if (countsAsMiss.test(result)) {
                settled = true;
                miss(who, reservation);
            }
            return result;
        } catch (VoucherCodeGuessException guess) {
            settled = true;
            miss(who, reservation);
            throw guess;
        } finally {
            if (!settled) {
                release(who, reservation);
            }
        }
    }

    private <T> T unkeyed(Supplier<T> body, Predicate<? super T> countsAsMiss) {
        try {
            T result = body.get();
            if (countsAsMiss.test(result)) {
                metrics.incVoucherGuardUnkeyed();
            }
            return result;
        } catch (VoucherCodeGuessException guess) {
            metrics.incVoucherGuardUnkeyed();
            throw guess;
        }
    }

    // ------------------------------------------------ reservation primitives

    /**
     * Reserves a slot for {@code who}; package-private for tests, which drive
     * the rules directly.
     *
     * @return the reservation to settle with {@link #miss} or {@link #release}
     * @throws VoucherAttemptsLockedException when refused
     */
    String admit(Identity who) {
        String reservation = UUID.randomUUID().toString();
        long[] verdict;
        try {
            verdict = admitInStore(who, reservation);
        } catch (RuntimeException unexpected) {
            // Belt and braces: every expected failure is handled inside. A bug
            // here must cost the lock check, never the redemption.
            log.warn("Voucher guard admit failed unexpectedly; allowing the attempt", unexpected);
            return reservation;
        }
        if (verdict[0] != ADMITTED) {
            metrics.incVoucherGuard("refused", who.kind());
            throw new VoucherAttemptsLockedException((verdict[1] + 999) / 1000);
        }
        return reservation;
    }

    /** Counts a reserved attempt as a miss. Never throws. */
    void miss(Identity who, String reservation) {
        try {
            metrics.incVoucherGuard("failures", who.kind());
            if (missInStore(who, reservation)) {
                metrics.incVoucherGuard("locked", who.kind());
                log.warn("Voucher redemption locked for {} after {} unknown codes within {}; lock lasts {}",
                        who.logSafe(), props.maxFailures(), props.window(), props.lockDuration());
            }
        } catch (RuntimeException unexpected) {
            log.warn("Voucher guard could not record a miss", unexpected);
        }
    }

    /** Gives a reserved attempt back — it was not a guess. Never throws. */
    void release(Identity who, String reservation) {
        fallback.release(who.id(), reservation);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForZSet().remove(inFlightKey(who), reservation);
        } catch (RuntimeException e) {
            // The reservation ages out of the window on its own; until then it
            // costs the caller one slot, never a lock.
            metrics.incVoucherGuardDegraded("release", "error");
            log.warn("Voucher guard: Redis unavailable to release a reservation ({})", e.toString());
        }
    }

    // ------------------------------------------------------------------ storage

    private long[] admitInStore(Identity who, String reservation) {
        String id = who.id();
        long now = clock.millis();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            metrics.incVoucherGuardDegraded("admit", "no_redis");
            return fallback.admit(id, reservation, now);
        }
        // A lock taken in memory while Redis was down still holds once it is
        // back. A MIRROR of a Redis lock does not count here, so a lock an
        // operator deleted from Redis is really gone.
        long ownLock = fallback.lockRemaining(id, now, false);
        if (ownLock > 0) {
            return new long[]{LOCKED, ownLock};
        }
        try {
            List<?> r = redis.execute(ADMIT_SCRIPT, keys(who),
                    Long.toString(props.window().toMillis()),
                    Long.toString(props.lockDuration().toMillis()),
                    Integer.toString(props.maxFailures()),
                    reservation);
            long state = asLong(r, 0);
            long ms = asLong(r, 1);
            if (state == LOCKED && ms > 0) {
                fallback.mirror(id, now + ms);
            }
            return new long[]{state, ms};
        } catch (RuntimeException e) {
            metrics.incVoucherGuardDegraded("admit", "error");
            log.warn("Voucher guard: Redis unavailable to admit an attempt ({}); using the in-memory store",
                    e.toString());
            return fallback.admit(id, reservation, now);
        }
    }

    /** @return true when this miss set a NEW lock */
    private boolean missInStore(Identity who, String reservation) {
        String id = who.id();
        long now = clock.millis();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            metrics.incVoucherGuardDegraded("miss", "no_redis");
            return fallback.miss(id, reservation, now);
        }
        try {
            List<?> r = redis.execute(MISS_SCRIPT, keys(who),
                    Long.toString(props.window().toMillis()),
                    Long.toString(props.lockDuration().toMillis()),
                    Integer.toString(props.maxFailures()),
                    reservation);
            long remaining = asLong(r, 1);
            if (remaining > 0) {
                fallback.mirror(id, now + remaining);
            }
            return asLong(r, 0) == 1;
        } catch (RuntimeException e) {
            metrics.incVoucherGuardDegraded("miss", "error");
            log.warn("Voucher guard: Redis unavailable to record a miss ({}); using the in-memory store",
                    e.toString());
            return fallback.miss(id, reservation, now);
        }
    }

    private static List<String> keys(Identity who) {
        return List.of(failuresKey(who), inFlightKey(who), lockKey(who));
    }

    static String failuresKey(Identity who) {
        return KEY_PREFIX + "{" + ref(who) + "}:fails";
    }

    static String inFlightKey(Identity who) {
        return KEY_PREFIX + "{" + ref(who) + "}:inflight";
    }

    static String lockKey(Identity who) {
        return KEY_PREFIX + "{" + ref(who) + "}:lock";
    }

    /**
     * The identity's reference in Redis keys and logs: the first 32 hex chars of
     * SHA-256 over {@code kind:value}. Keeps phone numbers out of the keyspace
     * (and out of {@code SCAN}/{@code MONITOR} output) while staying
     * reproducible for an operator: {@code printf 'phone:+263771234567' | sha256sum | cut -c1-32}.
     */
    static String ref(Identity who) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(who.id().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static long asLong(List<?> reply, int index) {
        if (reply == null || reply.size() <= index) {
            return 0;
        }
        Object o = reply.get(index);
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * The same rules as the Redis scripts, in memory. Correctness comes from the
     * stored timestamps, not from Caffeine's expiry, which only bounds memory —
     * so the clock is the one the guard was given and tests can drive it. Every
     * change to one identity's state happens inside one {@code compute}, which
     * Caffeine runs atomically per key.
     */
    static final class FallbackStore {

        /** Unlock instant, and whether it merely mirrors a lock Redis holds. */
        private record Lock(long unlockAtMs, boolean mirror) {
        }

        /** One identity's misses and in-flight reservations. Only touched inside compute. */
        private static final class Budget {
            final ArrayDeque<Long> misses = new ArrayDeque<>();
            final Map<String, Long> inFlight = new HashMap<>();

            void prune(long now, long windowMs) {
                while (!misses.isEmpty() && misses.peekFirst() <= now - windowMs) {
                    misses.pollFirst();
                }
                inFlight.values().removeIf(at -> at <= now - windowMs);
            }

            boolean isEmpty() {
                return misses.isEmpty() && inFlight.isEmpty();
            }

            long oldest(long fallback) {
                long oldest = misses.isEmpty() ? fallback : misses.peekFirst();
                for (long at : inFlight.values()) {
                    oldest = Math.min(oldest, at);
                }
                return oldest;
            }
        }

        private final VoucherGuardProperties props;
        private final Cache<String, Budget> budgets;
        private final Cache<String, Lock> locks;

        FallbackStore(VoucherGuardProperties props) {
            this.props = props;
            this.budgets = Caffeine.newBuilder()
                    .maximumSize(props.fallbackMaxEntries())
                    .expireAfterWrite(props.window())
                    .build();
            this.locks = Caffeine.newBuilder()
                    .maximumSize(props.fallbackMaxEntries())
                    .expireAfterWrite(props.lockDuration())
                    .build();
        }

        long lockRemaining(String id, long now, boolean includeMirrors) {
            Lock lock = locks.getIfPresent(id);
            if (lock == null || lock.unlockAtMs() <= now || (lock.mirror() && !includeMirrors)) {
                return 0;
            }
            return lock.unlockAtMs() - now;
        }

        void mirror(String id, long unlockAtMs) {
            locks.asMap().merge(id, new Lock(unlockAtMs, true),
                    // Never shorten a lock, and never demote one this store took itself.
                    (old, mirrored) -> old.unlockAtMs() >= mirrored.unlockAtMs() ? old
                            : new Lock(mirrored.unlockAtMs(), old.mirror()));
        }

        /**
         * @return {state, ms} as {@link #ADMIT_SCRIPT}
         *
         * <p>The lock is read INSIDE the identity's {@code compute}, never
         * before it. {@link #miss} trips the lock and clears the log inside the
         * same {@code compute}, so reading the lock first left a window in which
         * a concurrent miss locked the caller and emptied their log, and this
         * admit then saw an empty budget and let another guess through — a
         * burst of 40 got up to 11 lookups instead of 5.
         */
        long[] admit(String id, String reservation, long now) {
            long windowMs = props.window().toMillis();
            long[] verdict = {ADMITTED, 0};
            budgets.asMap().compute(id, (k, existing) -> {
                Budget b = existing == null ? new Budget() : existing;
                long locked = lockRemaining(id, now, true);
                if (locked > 0) {
                    verdict[0] = LOCKED;
                    verdict[1] = locked;
                    return b.isEmpty() ? null : b;
                }
                b.prune(now, windowMs);
                if (b.misses.size() + b.inFlight.size() >= props.maxFailures()) {
                    verdict[0] = BUSY;
                    verdict[1] = Math.max(1, b.oldest(now) + windowMs - now);
                } else {
                    b.inFlight.put(reservation, now);
                }
                return b.isEmpty() ? null : b;
            });
            return verdict;
        }

        /** @return true when this miss set a NEW lock */
        boolean miss(String id, String reservation, long now) {
            long windowMs = props.window().toMillis();
            boolean[] tripped = {false};
            budgets.asMap().compute(id, (k, existing) -> {
                Budget b = existing == null ? new Budget() : existing;
                b.inFlight.remove(reservation);
                if (lockRemaining(id, now, true) > 0) {
                    return b.isEmpty() ? null : b;       // never logged, never extended
                }
                b.prune(now, windowMs);
                b.misses.addLast(now);
                if (b.misses.size() >= props.maxFailures()) {
                    tripped[0] = true;
                    b.misses.clear();                    // the log restarts after the lock
                    // Set inside the compute so a concurrent admit, which reads
                    // the lock inside its own compute on this key, sees it.
                    locks.put(id, new Lock(now + props.lockDuration().toMillis(), false));
                }
                return b.isEmpty() ? null : b;
            });
            return tripped[0];
        }

        void release(String id, String reservation) {
            budgets.asMap().computeIfPresent(id, (k, b) -> {
                b.inFlight.remove(reservation);
                return b.isEmpty() ? null : b;
            });
        }
    }
}

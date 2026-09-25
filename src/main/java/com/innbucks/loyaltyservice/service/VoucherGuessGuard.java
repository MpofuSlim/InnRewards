package com.innbucks.loyaltyservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherGuardProperties;
import com.innbucks.loyaltyservice.exception.VoucherAttemptsLockedException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * Only {@link com.innbucks.loyaltyservice.exception.VoucherCodeGuessException}:
 * an unknown code, or someone else's live code presented by a customer. A
 * mistyped code (caught by the check digit), a double tap on a spent voucher, a
 * voucher at the wrong shop, an expired one — none of those are guesses. A
 * successful redemption does NOT clear the count (owner decision): otherwise a
 * guesser holding one real voucher could reset their budget with it.
 *
 * <h2>Storage — Redis first, memory as the fallback; never fail open, never 503</h2>
 * Redis holds a sliding log of misses per identity and the lock itself, each
 * updated by ONE atomic script. If Redis is absent or throws, an in-memory store
 * applies the same rules, so an outage neither removes the limit (fail-open)
 * nor refuses every redemption (fail-closed 503 on the spend path). Locks Redis
 * reports are mirrored locally, so a Redis blip in the middle of a lock does
 * not end it. With one replica the fallback is exactly as strict as Redis; with
 * N it loosens to N× until Redis is back, and a restart clears it.
 *
 * <p><b>Nothing escapes this class except {@link VoucherAttemptsLockedException}.</b>
 * It runs on the redemption path; a monitoring fault must never become a failed
 * redemption.
 *
 * <p>Redis keys carry a SHA-256 prefix of the identity, never the phone number
 * itself: {@code loyalty:voucher-guard:{<ref>}:lock}. To lift a lock by hand
 * (runbook), compute the ref the same way ({@link #ref}) and {@code DEL} both
 * keys; a lock taken while Redis was down lives in one replica's memory and
 * ends with its TTL or a restart.
 */
@Component
public class VoucherGuessGuard {

    private static final Logger log = LoggerFactory.getLogger(VoucherGuessGuard.class);

    private static final String KEY_PREFIX = "loyalty:voucher-guard:";

    /**
     * Read the lock's remaining TTL (ms), or -2 for "not locked". A lock with no
     * TTL cannot be set by {@link #RECORD_SCRIPT}, but if one ever exists it is
     * repaired here — a locked caller never reaches the record script, so this
     * is the only place that could stop it being permanent.
     */
    static final RedisScript<Long> GATE_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('PTTL', KEYS[1])
            if t == -1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              return tonumber(ARGV[1])
            end
            return t
            """, Long.class);

    /**
     * Record one miss and trip the lock at the threshold, atomically.
     * KEYS: failure log (ZSET), lock (STRING). ARGV: window ms, lock ms, max, nonce.
     * Returns {newlyLocked (1|0), remaining lock ms (0 = not locked)}.
     *
     * <ul>
     *   <li>Time is Redis's own ({@code TIME}), so replicas with skewed clocks
     *   share one window.</li>
     *   <li>A miss while already locked is NOT logged and does NOT extend the
     *   lock, so {@code Retry-After} is truthful and every lock ends.</li>
     *   <li>Both keys always carry a TTL; the log never holds more than
     *   {@code max} entries.</li>
     * </ul>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final RedisScript<List> RECORD_SCRIPT = new DefaultRedisScript<>("""
            local tm = redis.call('TIME')
            local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
            local win, lockms, max = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            local ttl = redis.call('PTTL', KEYS[2])
            if ttl == -1 then
              redis.call('PEXPIRE', KEYS[2], lockms)
              return {0, lockms}
            end
            if ttl > 0 then
              return {0, ttl}
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
            redis.call('ZADD', KEYS[1], now, tm[1] .. tm[2] .. ':' .. ARGV[4])
            redis.call('PEXPIRE', KEYS[1], win)
            if redis.call('ZCARD', KEYS[1]) >= max then
              redis.call('SET', KEYS[2], '1', 'PX', lockms)
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

    /**
     * The authenticated caller's identity, read from the security context only.
     * Empty for a caller with none — which no role allowed to redeem should be,
     * so it is counted ({@code loyalty.voucher.guard.unkeyed}) rather than guessed.
     */
    public Optional<Identity> callerIdentity() {
        if (CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_MERCHANT_ADMIN",
                "ROLE_SHOP_ADMIN", "ROLE_SHOP_USER")) {
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
     * Refuses a locked caller BEFORE any lookup, so a locked caller learns
     * nothing and costs no database connection or row lock. A correct code is
     * refused too while the lock lasts — that is what makes it stop guessing.
     *
     * @throws VoucherAttemptsLockedException when the caller is locked
     */
    public void checkNotLocked(Optional<Identity> identity) {
        if (!props.enabled() || identity.isEmpty()) {
            return;
        }
        long remainingMs;
        try {
            remainingMs = lockRemainingMs(identity.get());
        } catch (RuntimeException unexpected) {
            // Belt and braces: every expected failure is handled inside. A bug
            // here must cost the lock check, never the redemption.
            log.warn("Voucher guard check failed unexpectedly; allowing the attempt", unexpected);
            return;
        }
        if (remainingMs > 0) {
            metrics.incVoucherGuard("refused", identity.get().kind());
            throw new VoucherAttemptsLockedException((remainingMs + 999) / 1000);
        }
    }

    /** Counts one guess against the caller. Never throws. */
    public void recordFailure(Optional<Identity> identity) {
        if (!props.enabled()) {
            return;
        }
        if (identity.isEmpty()) {
            metrics.incVoucherGuardUnkeyed();
            return;
        }
        try {
            Identity who = identity.get();
            metrics.incVoucherGuard("failures", who.kind());
            if (recordMiss(who)) {
                metrics.incVoucherGuard("locked", who.kind());
                log.warn("Voucher redemption locked for {} after {} unknown codes within {}; "
                                + "lock lasts {}",
                        who.logSafe(), props.maxFailures(), props.window(), props.lockDuration());
            }
        } catch (RuntimeException unexpected) {
            log.warn("Voucher guard could not record a miss", unexpected);
        }
    }

    // ------------------------------------------------------------------ storage

    private long lockRemainingMs(Identity who) {
        String id = who.id();
        long now = clock.millis();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            metrics.incVoucherGuardDegraded("check", "no_redis");
            return fallback.lockRemaining(id, now, true);
        }
        try {
            Long ttl = redis.execute(GATE_SCRIPT, List.of(lockKey(who)),
                    Long.toString(props.lockDuration().toMillis()));
            long redisMs = ttl == null ? 0 : Math.max(0, ttl);
            if (redisMs > 0) {
                fallback.mirror(id, now + redisMs);
            }
            // A lock taken in memory while Redis was down still counts once it
            // is back; a MIRROR of a Redis lock does not, so a lock an operator
            // deleted from Redis is really gone.
            return Math.max(redisMs, fallback.lockRemaining(id, now, false));
        } catch (RuntimeException e) {
            metrics.incVoucherGuardDegraded("check", "error");
            log.warn("Voucher guard: Redis unavailable for the lock check ({}); using the in-memory store",
                    e.toString());
            return fallback.lockRemaining(id, now, true);
        }
    }

    /** @return true when this miss set a NEW lock */
    private boolean recordMiss(Identity who) {
        String id = who.id();
        long now = clock.millis();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            metrics.incVoucherGuardDegraded("record", "no_redis");
            return fallback.recordMiss(id, now);
        }
        try {
            List<?> result = redis.execute(RECORD_SCRIPT, List.of(failuresKey(who), lockKey(who)),
                    Long.toString(props.window().toMillis()),
                    Long.toString(props.lockDuration().toMillis()),
                    Integer.toString(props.maxFailures()),
                    UUID.randomUUID().toString());
            boolean newlyLocked = result != null && result.size() == 2 && asLong(result.get(0)) == 1;
            long remaining = result != null && result.size() == 2 ? asLong(result.get(1)) : 0;
            if (remaining > 0) {
                fallback.mirror(id, now + remaining);
            }
            return newlyLocked;
        } catch (RuntimeException e) {
            metrics.incVoucherGuardDegraded("record", "error");
            log.warn("Voucher guard: Redis unavailable to record a miss ({}); using the in-memory store",
                    e.toString());
            return fallback.recordMiss(id, now);
        }
    }

    static String failuresKey(Identity who) {
        return KEY_PREFIX + "{" + ref(who) + "}:fails";
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

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * The same rules as the Redis scripts, in memory. Correctness comes from the
     * stored timestamps, not from Caffeine's expiry, which only bounds memory —
     * so the clock is the one the guard was given and tests can drive it.
     */
    static final class FallbackStore {

        /** Unlock instant, and whether it merely mirrors a lock Redis holds. */
        private record Lock(long unlockAtMs, boolean mirror) {
        }

        private final VoucherGuardProperties props;
        private final Cache<String, ArrayDeque<Long>> failures;
        private final Cache<String, Lock> locks;

        FallbackStore(VoucherGuardProperties props) {
            this.props = props;
            this.failures = Caffeine.newBuilder()
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
                            : new Lock(mirrored.unlockAtMs(), old.mirror() && mirrored.mirror()));
        }

        /** @return true when this miss set a NEW lock */
        boolean recordMiss(String id, long now) {
            if (lockRemaining(id, now, true) > 0) {
                return false;                       // already locked: never extended
            }
            long windowMs = props.window().toMillis();
            boolean[] tripped = {false};
            failures.asMap().compute(id, (k, log) -> {
                ArrayDeque<Long> misses = log == null ? new ArrayDeque<>() : log;
                while (!misses.isEmpty() && misses.peekFirst() <= now - windowMs) {
                    misses.pollFirst();
                }
                misses.addLast(now);
                if (misses.size() >= props.maxFailures()) {
                    tripped[0] = true;
                    return null;                    // the log restarts after the lock
                }
                return misses;
            });
            if (tripped[0]) {
                locks.put(id, new Lock(now + props.lockDuration().toMillis(), false));
            }
            return tripped[0];
        }
    }
}

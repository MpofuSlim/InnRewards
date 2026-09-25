package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code loyalty.voucher.redeem-guard.*} — the voucher-guessing lockout.
 * Owner decision (2026-09-25): <b>5 unknown codes within one minute locks that
 * caller out of voucher redemption for 30 minutes.</b>
 *
 * <p>Kept apart from {@link LoyaltyProperties.Voucher}, whose record is built
 * positionally in several tests. Fields are BOXED with defaults applied in the
 * constructor: a missing primitive {@code int} would bind as {@code 0}, and a
 * threshold of 0 locks every caller on their first miss.
 *
 * @param enabled            false turns the lockout off entirely (no checks, no counting)
 * @param maxFailures        counted misses within {@code window} that trip the lock
 * @param window             sliding window the misses are counted over
 * @param lockDuration       how long a tripped lock lasts; attempts made while locked never extend it
 * @param fallbackMaxEntries bound on the in-memory store used when Redis is unavailable
 */
@ConfigurationProperties("loyalty.voucher.redeem-guard")
public record VoucherGuardProperties(Boolean enabled,
                                     Integer maxFailures,
                                     Duration window,
                                     Duration lockDuration,
                                     Integer fallbackMaxEntries) {

    public VoucherGuardProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxFailures = maxFailures == null ? 5 : maxFailures;
        window = window == null ? Duration.ofMinutes(1) : window;
        lockDuration = lockDuration == null ? Duration.ofMinutes(30) : lockDuration;
        fallbackMaxEntries = fallbackMaxEntries == null ? 100_000 : fallbackMaxEntries;
        if (maxFailures < 1) {
            throw new IllegalArgumentException(
                    "loyalty.voucher.redeem-guard.max-failures must be at least 1 (was " + maxFailures + ")");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("loyalty.voucher.redeem-guard.window must be positive");
        }
        if (lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("loyalty.voucher.redeem-guard.lock-duration must be positive");
        }
        if (fallbackMaxEntries < 1) {
            throw new IllegalArgumentException(
                    "loyalty.voucher.redeem-guard.fallback-max-entries must be at least 1");
        }
    }

    /** The defaults: on, 5 per minute, 30-minute lock. */
    public static VoucherGuardProperties defaults() {
        return new VoucherGuardProperties(null, null, null, null, null);
    }
}

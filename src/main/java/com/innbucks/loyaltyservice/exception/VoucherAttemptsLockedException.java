package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller tried too many unknown voucher codes and is locked out of voucher
 * redemption for a while. Rendered as {@code 429} with a {@code Retry-After}
 * header and {@code data.retryAfterSeconds} by {@code GlobalExceptionHandler}.
 *
 * <p>The message is the same whichever identity tripped the lock, so the
 * response never tells a caller which of their identifiers is being watched.
 */
public class VoucherAttemptsLockedException extends LoyaltyException {

    public static final String CODE = "VOUCHER_ATTEMPTS_LOCKED";
    public static final String MESSAGE =
            "Too many incorrect voucher codes were tried. Please wait and try again later.";

    private final long retryAfterSeconds;

    public VoucherAttemptsLockedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, CODE, MESSAGE);
        // Never 0: "Retry-After: 0" invites an immediate retry that will be refused.
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

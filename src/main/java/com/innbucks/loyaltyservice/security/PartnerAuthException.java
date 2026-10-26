package com.innbucks.loyaltyservice.security;

/**
 * A partner API request that failed authentication (V62). The {@link #reason}
 * goes to the log and the {@code loyalty.partner.rejected{reason}} counter only;
 * the caller always gets the same opaque 401, so it learns nothing about which
 * check failed.
 */
public class PartnerAuthException extends Exception {

    private final String reason;

    public PartnerAuthException(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    /** Redis could not record the {@code jti}: answered 503, never let through. */
    public static class ReplayCheckUnavailable extends Exception {
        public ReplayCheckUnavailable(Throwable cause) {
            super("replay check unavailable", cause, false, false);
        }
    }
}

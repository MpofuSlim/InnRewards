package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

public class LoyaltyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public LoyaltyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static LoyaltyException notFound(String what) {
        // Fleet-standard status-format code ("404 NOT_FOUND", per ApiResult.error)
        // so runtime matches the controllers' Swagger @ExampleObject bodies and
        // the other services' 404s. badRequest/conflict/forbidden below keep
        // their explicit DOMAIN codes (e.g. MERCHANT_NAME_TAKEN) by design.
        return new LoyaltyException(HttpStatus.NOT_FOUND, "404 NOT_FOUND", what + " not found");
    }

    public static LoyaltyException badRequest(String code, String message) {
        return new LoyaltyException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static LoyaltyException conflict(String code, String message) {
        return new LoyaltyException(HttpStatus.CONFLICT, code, message);
    }

    public static LoyaltyException forbidden(String code, String message) {
        return new LoyaltyException(HttpStatus.FORBIDDEN, code, message);
    }

    /**
     * 401 for an endpoint that authenticates its caller itself rather than
     * through Spring Security — the partner registration endpoint's assertion /
     * shared-key check. Keep the message generic: naming which check failed
     * hands an attacker a free oracle.
     */
    public static LoyaltyException unauthorized(String code, String message) {
        return new LoyaltyException(HttpStatus.UNAUTHORIZED, code, message);
    }

    /**
     * 503 for a feature that is switched on but not provisioned — a
     * half-provisioned cell. Distinct from 404 (switched off, nothing to see)
     * so an operator can tell "I never enabled this" from "I enabled it and
     * forgot the credential".
     */
    public static LoyaltyException serviceUnavailable(String code, String message) {
        return new LoyaltyException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

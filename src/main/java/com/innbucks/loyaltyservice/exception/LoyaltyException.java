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
        return new LoyaltyException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
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

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

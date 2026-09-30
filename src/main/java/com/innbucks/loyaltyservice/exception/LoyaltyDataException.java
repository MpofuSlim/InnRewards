package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A {@link LoyaltyException} whose envelope carries a {@code data} payload
 * instead of {@code null} — for a refusal the client acts on beyond its code:
 * a rate limit names its scope and window, and an undelivered support message
 * returns the record of the attempt it just wrote.
 *
 * <p>Handled by its own {@code GlobalExceptionHandler} method, which Spring
 * picks over the {@link LoyaltyException} one because it is more specific;
 * through that one the status and code would survive but {@code data} would be
 * dropped. {@code GlobalExceptionHandlerDispatchTest} pins the choice.
 */
public class LoyaltyDataException extends LoyaltyException {

    private final transient Object data;

    public LoyaltyDataException(HttpStatus status, String code, String message, Object data) {
        super(status, code, message);
        this.data = data;
    }

    public Object getData() {
        return data;
    }
}

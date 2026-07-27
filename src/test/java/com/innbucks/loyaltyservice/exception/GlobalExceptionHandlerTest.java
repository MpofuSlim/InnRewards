package com.innbucks.loyaltyservice.exception;

import com.innbucks.loyaltyservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the contract of {@link GlobalExceptionHandler}: every error is the
 * fleet's {@link ApiResult} envelope ({@code {code, message, data}}), the
 * generic handlers use the fleet's {@code "<value> <NAME>"} code, and
 * {@link LoyaltyException} keeps its intentional domain code. Most paths are
 * also exercised end-to-end by the controller security tests; this file pins
 * the handlers without a Spring context.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void loyaltyException_preservesDomainCodeInApiResultEnvelope() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                LoyaltyException.conflict("MERCHANT_NAME_TAKEN", "A merchant with that name already exists"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Domain code preserved (the FE switches on it) — not overwritten with a
        // status-format code.
        assertEquals("MERCHANT_NAME_TAKEN", resp.getBody().getCode());
        assertEquals("A merchant with that name already exists", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void responseStatusException_honoursEmbeddedStatusAndReason_notSwallowedByCatchAll() {
        // ResponseStatusException extends RuntimeException — without the
        // dedicated handler the Exception catch-all swallows it as 500 with
        // a generic "internal error", turning every deliberate 4xx into an
        // opaque server error to the client.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "voucher not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("404 NOT_FOUND", resp.getBody().getCode());
        assertEquals("voucher not found", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void responseStatusException_fallsBackToStatusReasonPhrase_whenReasonIsNull() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new ResponseStatusException(HttpStatus.CONFLICT));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Reason phrase from HttpStatus when ex.getReason() is null — never a
        // null body.message that would crash the FE's renderer.
        assertEquals("Conflict", resp.getBody().getMessage());
        assertEquals("409 CONFLICT", resp.getBody().getCode());
    }

    @Test
    void uncaughtException_returnsGenericInternal_noLeak() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                (Exception) new RuntimeException("internal: connection pool exhausted at Lettuce"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("500 INTERNAL_SERVER_ERROR", resp.getBody().getCode());
        assertEquals("Something went wrong on our end. Please try again.", resp.getBody().getMessage());
        // Internal detail MUST NOT reach the client.
        String msg = String.valueOf(resp.getBody().getMessage());
        assertFalse(msg.contains("Lettuce") || msg.contains("connection pool"),
                "500 body must not leak the internal exception detail");
    }

    @Test
    void malformedBody_returns400_notSwallowedByCatchAll() {
        // The real prod case: a non-UUID string for a UUID field. A bad request
        // body is a client error → 400, not the catch-all 500.
        var ex = new org.springframework.http.converter.HttpMessageNotReadableException(
                "JSON parse error",
                new RuntimeException("UUID has to be represented by standard 36-char representation"),
                (org.springframework.http.HttpInputMessage) null);
        ResponseEntity<ApiResult<Void>> resp = handler.handle(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("400 BAD_REQUEST", resp.getBody().getCode());
        // The raw Jackson parse detail must not leak to the client.
        String msg = String.valueOf(resp.getBody().getMessage());
        assertFalse(msg.contains("36-char"), "400 body must not leak the raw Jackson parse detail");
    }

    @Test
    void unmappedRoute_returns404_notSwallowedByCatchAll() {
        // The handler returns a static 404 regardless of the exception's contents,
        // so a mock avoids coupling to the Spring-version-specific constructor.
        var ex = org.mockito.Mockito.mock(org.springframework.web.servlet.resource.NoResourceFoundException.class);
        ResponseEntity<ApiResult<Void>> resp = handler.handle(ex);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("404 NOT_FOUND", resp.getBody().getCode());
    }
}

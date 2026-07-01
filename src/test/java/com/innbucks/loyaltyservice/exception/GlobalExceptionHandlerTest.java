package com.innbucks.loyaltyservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the contract of {@link GlobalExceptionHandler}. Most paths
 * (LoyaltyException, AccessDenied, Authentication, MethodArgumentNotValid)
 * are exercised end-to-end by the controller security tests; this file
 * exists to pin the handlers without a Spring context.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusException_honoursEmbeddedStatusAndReason_notSwallowedByCatchAll() {
        // ResponseStatusException extends RuntimeException — without the
        // dedicated handler the Exception catch-all swallows it as 500 with
        // a generic "internal error", turning every deliberate 4xx into an
        // opaque server error to the client.
        ResponseEntity<Map<String, Object>> resp = handler.handle(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "voucher not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(404, resp.getBody().get("status"));
        assertEquals("voucher not found", resp.getBody().get("message"));
    }

    @Test
    void responseStatusException_fallsBackToStatusReasonPhrase_whenReasonIsNull() {
        ResponseEntity<Map<String, Object>> resp = handler.handle(
                new ResponseStatusException(HttpStatus.CONFLICT));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Reason phrase from HttpStatus when ex.getReason() is null — never a
        // null body.message that would crash the FE's renderer.
        assertEquals("Conflict", resp.getBody().get("message"));
    }

    @Test
    void uncaughtException_returnsGenericInternal_noLeak() {
        ResponseEntity<Map<String, Object>> resp = handler.handle(
                (Exception) new RuntimeException("internal: connection pool exhausted at Lettuce"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Something went wrong on our end. Please try again.", resp.getBody().get("message"));
        // Internal detail MUST NOT reach the client.
        assertEquals(500, resp.getBody().get("status"));
        String msg = String.valueOf(resp.getBody().get("message"));
        org.junit.jupiter.api.Assertions.assertFalse(msg.contains("Lettuce") || msg.contains("connection pool"),
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
        ResponseEntity<Map<String, Object>> resp = handler.handle(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(400, resp.getBody().get("status"));
        assertEquals("BAD_REQUEST", resp.getBody().get("code"));
        // The raw Jackson parse detail must not leak to the client.
        String msg = String.valueOf(resp.getBody().get("message"));
        org.junit.jupiter.api.Assertions.assertFalse(msg.contains("36-char"),
                "400 body must not leak the raw Jackson parse detail");
    }

    @Test
    void unmappedRoute_returns404_notSwallowedByCatchAll() {
        // The handler returns a static 404 regardless of the exception's contents,
        // so a mock avoids coupling to the Spring-version-specific constructor.
        var ex = org.mockito.Mockito.mock(org.springframework.web.servlet.resource.NoResourceFoundException.class);
        ResponseEntity<Map<String, Object>> resp = handler.handle(ex);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(404, resp.getBody().get("status"));
        assertEquals("NOT_FOUND", resp.getBody().get("code"));
    }
}

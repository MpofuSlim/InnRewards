package com.innbucks.loyaltyservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Method-level @PreAuthorize throws AuthorizationDeniedException (a subclass
    // of AccessDeniedException in Spring Security 6). Without this handler it
    // falls through to the generic Exception handler below and returns 500
    // instead of 403, masking permission errors as server errors.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 403,
                "code", "FORBIDDEN",
                "message", "You don't have permission to do that."
        ));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handle(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "code", "UNAUTHORIZED",
                "message", "Please sign in to continue."
        ));
    }

    @ExceptionHandler(LoyaltyException.class)
    public ResponseEntity<Map<String, Object>> handle(LoyaltyException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", ex.getStatus().value(),
                "code", ex.getCode(),
                "message", ex.getMessage()
        ));
    }

    /**
     * @Valid bean-validation failures on @RequestBody. Returns the project's
     * standard ApiResult envelope with field-level messages in data — same
     * shape as the matching handler in user-service / seat-service /
     * booking-service / event-service / payment-service so the frontend has
     * one render path for validation errors across the API.
     *
     * <p>Previously returned a raw Map with a top-level "fields" key — that
     * worked but differed from every other service. data: { field → msg }
     * is now the canonical shape.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.innbucks.loyaltyservice.dto.ApiResult<Map<String, String>>> handle(
            MethodArgumentNotValidException ex) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        log.warn("Validation failed fields={}", fields);
        return ResponseEntity.badRequest().body(
                com.innbucks.loyaltyservice.dto.ApiResult.<Map<String, String>>builder()
                        .code("400 BAD_REQUEST")
                        .message("Validation failed")
                        .data(fields)
                        .build());
    }

    /**
     * Spring's {@link ResponseStatusException} extends RuntimeException, so
     * without this handler it would be swallowed by the {@code Exception}
     * catch-all below and surface as a sanitised 500. Honour the embedded
     * status / reason instead. Prefer the typed {@link LoyaltyException}
     * factories at throw sites; this is a defence-in-depth net for Spring's
     * own / library code.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("ResponseStatusException status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "code", status.name(),
                "message", reason
        ));
    }

    /**
     * Malformed / unreadable request body — bad JSON, or a value that can't map
     * to the target type (e.g. a non-UUID string for a UUID field). A CLIENT
     * error, so 400 — not the 500 the Exception catch-all below would otherwise
     * produce. The raw Jackson cause can leak internal type details, so we log
     * it and return a clean generic message.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "code", "BAD_REQUEST",
                "message", "Malformed or unreadable request body."
        ));
    }

    /**
     * No route matched the request path (e.g. a removed or mistyped endpoint).
     * Spring raises NoResourceFoundException; without this it hits the Exception
     * catch-all and surfaces as a 500. A missing route is a client error → 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 404,
                "code", "NOT_FOUND",
                "message", "The requested resource was not found."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        // Don't swallow the cause: a 500 with an opaque body is hard enough to
        // diagnose in prod without the stack trace also being missing from the
        // logs. The response stays generic so we don't leak internals; the log
        // is where on-call goes to find the real problem.
        //
        // Note: this used to be preceded by an @ExceptionHandler(IllegalArgumentException)
        // that returned 400 with the raw exception message. The 400-mapped
        // path was unused — every deliberate 4xx in loyalty-service goes
        // through LoyaltyException.{notFound,badRequest,conflict,forbidden} —
        // so the handler only fired on accidental IAEs from the JDK / libraries,
        // and leaked their (often-internal) messages to the client as a 400.
        // Removed: an accidental IAE now falls through to here and produces
        // the same sanitised 500 a NullPointerException would.
        log.error("Unhandled exception bubbled to GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 500,
                "code", "INTERNAL",
                "message", "Something went wrong on our end. Please try again."
        ));
    }
}

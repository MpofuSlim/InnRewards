package com.innbucks.loyaltyservice.exception;

import com.innbucks.loyaltyservice.dto.ApiResult;
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

import java.util.Map;

/**
 * Every error response uses the fleet's standard {@link ApiResult} envelope
 * ({@code {code, message, data}}) — the SAME shape the six ticketing-system
 * services emit and the same shape this service's own Swagger {@code @ExampleObject}
 * bodies document, so the frontend has one render path for errors across the API.
 *
 * <p>Previously these handlers returned a raw {@code {timestamp, status, code,
 * message}} Map, which diverged from every other service (extra keys, missing
 * {@code data}, and a bare {@code code} like {@code NOT_FOUND} instead of the
 * fleet's {@code 404 NOT_FOUND}). Generic handlers now use
 * {@link ApiResult#error(HttpStatus, String)} (code = "{@code <value> <NAME>}");
 * {@link LoyaltyException} keeps its intentional domain code (e.g.
 * {@code MERCHANT_NAME_TAKEN}) since those are part of the documented contract.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Method-level @PreAuthorize throws AuthorizationDeniedException (a subclass
    // of AccessDeniedException in Spring Security 6). Without this handler it
    // falls through to the generic Exception handler below and returns 500
    // instead of 403, masking permission errors as server errors.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handle(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error(HttpStatus.FORBIDDEN, "You don't have permission to do that."));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResult<Void>> handle(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
    }

    @ExceptionHandler(LoyaltyException.class)
    public ResponseEntity<ApiResult<Void>> handle(LoyaltyException ex) {
        // Preserve the intentional domain code (e.g. MERCHANT_NAME_TAKEN) — it's
        // part of the documented contract the FE switches on — in the standard
        // ApiResult envelope.
        return ResponseEntity.status(ex.getStatus()).body(
                ApiResult.<Void>builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    /**
     * @Valid bean-validation failures on @RequestBody. ApiResult with field-level
     * messages in {@code data} — same shape as the matching handler in
     * user-service / seat-service / booking-service / event-service /
     * payment-service so the frontend has one render path for validation errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        log.warn("Validation failed fields={}", fields);
        return ResponseEntity.badRequest().body(
                ApiResult.<Map<String, String>>builder()
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
    public ResponseEntity<ApiResult<Void>> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("ResponseStatusException status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).body(ApiResult.error(status, reason));
    }

    /**
     * Malformed / unreadable request body — bad JSON, or a value that can't map
     * to the target type (e.g. a non-UUID string for a UUID field). A CLIENT
     * error, so 400 — not the 500 the Exception catch-all below would otherwise
     * produce. The raw Jackson cause can leak internal type details, so we log
     * it and return a clean generic message.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handle(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body."));
    }

    /**
     * No route matched the request path (e.g. a removed or mistyped endpoint).
     * Spring raises NoResourceFoundException; without this it hits the Exception
     * catch-all and surfaces as a 500. A missing route is a client error → 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handle(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(HttpStatus.NOT_FOUND, "The requested resource was not found."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handle(Exception ex) {
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Something went wrong on our end. Please try again."));
    }
}

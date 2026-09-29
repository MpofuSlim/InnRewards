package com.innbucks.loyaltyservice.exception;

import com.innbucks.loyaltyservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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

    /**
     * The voucher redeem lockout. More specific than the {@link LoyaltyException}
     * handler below, so Spring picks this one. {@code Retry-After} is the header
     * clients and proxies honour; {@code data.retryAfterSeconds} carries the same
     * number for a browser client whose CORS config does not expose the header.
     * {@code no-store} so no cache ever replays a lockout after it has ended.
     */
    @ExceptionHandler(VoucherAttemptsLockedException.class)
    public ResponseEntity<ApiResult<RetryAfterDetail>> handle(VoucherAttemptsLockedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.<RetryAfterDetail>builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .data(new RetryAfterDetail(ex.getRetryAfterSeconds()))
                        .build());
    }

    /** {@code data} of a 429: how long until the caller may try again. */
    public record RetryAfterDetail(long retryAfterSeconds) {
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
     * A request parameter or path variable that could not be converted to the
     * handler's declared type — {@code ?status=FOO}, a non-UUID id in the path,
     * {@code ?page=abc}. Spring's own {@code DefaultHandlerExceptionResolver}
     * maps this to 400, but on this service it never gets the chance: the
     * {@code @ExceptionHandler} resolver is consulted FIRST, so the
     * {@code Exception} catch-all below shadowed it and every mistyped
     * parameter surfaced as an opaque 500 — "Something went wrong on our end.
     * Please try again.", which invites a retry that can never succeed and
     * reads as a service fault when the request was simply malformed. Same
     * reasoning as the two handlers above; this is the parameter-binding case.
     *
     * <p>The message names the parameter and, for an enum target, the values it
     * accepts — a client cannot correct a rejected value it is never told the
     * shape of. The rejected value itself is deliberately NOT echoed (it is
     * caller-controlled and would be reflected into the response body), and the
     * conversion cause, which can carry internal type detail, is logged rather
     * than returned. That is the narrow version of the
     * {@code IllegalArgumentException} handler the catch-all's note below
     * records as removed for leaking library messages: bound by type to
     * parameter binding, with a message we author.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handle(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.warn("Unconvertible request parameter name={} requiredType={}: {}", ex.getName(),
                ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName(),
                ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, unconvertibleParameterMessage(ex)));
    }

    private static String unconvertibleParameterMessage(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        if (required != null && required.isEnum()) {
            StringBuilder accepted = new StringBuilder();
            for (Object constant : required.getEnumConstants()) {
                if (!accepted.isEmpty()) accepted.append(", ");
                accepted.append(((Enum<?>) constant).name());
            }
            return "Invalid value for '" + ex.getName() + "'. Accepted values: " + accepted + ".";
        }
        return "Invalid value for '" + ex.getName() + "'.";
    }

    /**
     * A required request parameter that is absent — or, the case that actually
     * reaches us from a browser, PRESENT BUT BLANK. Both are 400s that the
     * {@code Exception} catch-all was answering with a 500, for the same
     * resolver-ordering reason as the handler above.
     *
     * <p>The blank case is the one worth knowing about, because it is not a
     * developer typo: an enum-typed parameter converts an empty string to
     * {@code null} (Spring's own converter factory does this, and so does the
     * fallback in {@code TypeConverterDelegate} when a custom converter
     * refuses), and {@code RequestParamMethodArgumentResolver} then rejects a
     * required parameter that "is present but converted to null". So a console
     * sending {@code ?status=} for an "All" tab — a natural thing for a filter
     * UI to do — got a 500. `VoucherStatusConverter` is NOT the cause; the
     * default binder produced the identical exception before it existed.
     *
     * <p>The two cases get different messages because they need different
     * fixes: send the parameter, versus stop sending it empty.
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handle(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        String message = ex.isMissingAfterConversion()
                ? "Parameter '" + ex.getParameterName() + "' was sent with no value. "
                        + "Give it a value, or omit the parameter entirely."
                : "Required parameter '" + ex.getParameterName() + "' is missing.";
        log.warn("Missing request parameter name={} afterConversion={}",
                ex.getParameterName(), ex.isMissingAfterConversion());
        return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST, message));
    }

    /**
     * The rest of the request-binding family — a missing required header, cookie
     * or matrix variable. Spring maps every one of them to 400; the catch-all
     * was turning them into 500s. Nothing in {@code src/main} declares a
     * {@code @RequestHeader} today (the tenant headers are read by
     * {@code TenantContext}, which throws a typed {@link LoyaltyException}), so
     * this has no live caller — it is here so the next one added is correct by
     * default rather than by remembering. The message is generic because this
     * type exposes no accessor for what was missing; the specific case worth a
     * tailored message has its own handler above.
     */
    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    public ResponseEntity<ApiResult<Void>> handle(
            org.springframework.web.bind.ServletRequestBindingException ex) {
        log.warn("Request binding failed: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                "The request is missing something it requires. Check the required headers and parameters."));
    }

    /**
     * The path exists but not for that verb — a {@code POST} to a read-only
     * endpoint. The last member of the shadowed-by-the-catch-all family, and a
     * 500 for the same reason as the three above it.
     *
     * <p>The {@code Allow} header is the load-bearing half: a 405 without it
     * tells the caller only that they were wrong, never what would be right,
     * and it is what an HTTP client or a generated SDK actually reads. It is
     * set from OUR mapping, so nothing caller-supplied reaches the response —
     * the attempted method is a caller-controlled token and is deliberately
     * neither echoed in the body nor named in the message. An empty or absent
     * supported set emits no header rather than a blank one.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handle(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        java.util.Set<org.springframework.http.HttpMethod> supported = ex.getSupportedHttpMethods();
        log.warn("Method not allowed: supported={}", supported);

        String message = supported == null || supported.isEmpty()
                ? "That HTTP method isn't supported on this path."
                : "That HTTP method isn't supported on this path. Allowed: "
                        + supported.stream().map(org.springframework.http.HttpMethod::name).sorted()
                                .collect(java.util.stream.Collectors.joining(", ")) + ".";

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new org.springframework.http.HttpMethod[0]));
        }
        return response.body(ApiResult.error(HttpStatus.METHOD_NOT_ALLOWED, message));
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

    /**
     * A DB constraint tripped at commit — almost always a unique-index race that
     * the pre-check missed (e.g. two identical idempotency-keyed writes landing
     * together). That is a conflict, not a server fault: map it to 409 rather
     * than letting it fall to the catch-all as an opaque 500. The service layer
     * catches the ones it can name (RedemptionService / TransactionService turn a
     * duplicate reference into DUPLICATE_REFERENCE up front); this is the backstop
     * for anything that slips past the pre-check to the commit boundary.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResult<Void>> handle(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Data integrity violation mapped to 409", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(HttpStatus.CONFLICT,
                        "This request conflicts with an existing record and was not applied. "
                                + "If you are retrying, the original may already have succeeded."));
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

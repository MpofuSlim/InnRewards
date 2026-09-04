package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.LoyaltySessionIssuer;
import com.innbucks.loyaltyservice.service.LoyaltySessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a customer's loyalty session alive without a second phone proof (V43).
 *
 * <h2>The problem this closes</h2>
 * A loyalty session lasts twelve hours and had no renewal path, so the app had
 * to obtain a fresh phone proof twice a day. Registration is a PERMANENT
 * phone-level fact (V40) and the only live proof channel is an SMS OTP, so
 * "one SMS per customer for life" was in practice one SMS every twelve hours,
 * per device — the cost that kept the customer app on the unauthenticated
 * {@code /loyalty/public/**} surface.
 *
 * <h2>The three calls</h2>
 * <ul>
 *   <li>{@code POST /loyalty/session/exchange} — <b>bearer: a live loyalty
 *       session</b>. Trades it for a renewable one. Called ONCE, right after the
 *       OTP verify that minted the session.</li>
 *   <li>{@code POST /loyalty/session/refresh} — <b>credential: the refresh token
 *       in the body</b>. Rotates it and returns a fresh access token.</li>
 *   <li>{@code POST /loyalty/session/logout} — same credential, ends the
 *       chain.</li>
 * </ul>
 *
 * <h2>Why refresh and logout carry no Authorization header</h2>
 * Their credential IS the refresh token, and the access token they exist to
 * replace is expired by the time they are called — requiring a live bearer would
 * make renewal possible only while renewal was unnecessary. They are
 * {@code permitAll} in {@code SecurityConfig} for exactly that reason, scoped to
 * the exact method + path so nothing else under this prefix inherits it, and the
 * gateway fronts them with an IP-keyed fail-safe limiter because an
 * unauthenticated endpoint that accepts a secret is what a guesser aims at.
 *
 * <h2>Why exchange is gated on the scope marker, not merely on being logged in</h2>
 * A chain is a long-lived, phone-scoped credential. Only a caller that ALREADY
 * holds a phone-proved session may open one, so the guard names the scope
 * markers {@code JwtFilter} grants for those tokens
 * ({@code SERVICE_LOYALTY-OTP} / {@code SERVICE_LOYALTY-SESSION}) rather than
 * {@code isAuthenticated()}. A staff or admin token cannot walk this path: its
 * phone claim is an employee's number, and letting it mint a customer-shaped
 * chain would be an escalation dressed up as a convenience.
 *
 * <p>The exchange grants nothing new either way — the caller already holds a
 * session for that phone, and the phone comes from their own token claim, never
 * from a request field.
 */
@RestController
@RequestMapping("/loyalty/session")
@Slf4j
@Tag(name = "Session",
     description = "Renew a phone-scoped loyalty session without a second phone proof. "
                 + "Exchange a live session for a rotating refresh token, then refresh it.")
public class LoyaltySessionController {

    /**
     * The authorities {@code JwtFilter} grants for the two phone-scoped scope
     * markers. It uppercases each {@code services} entry behind a
     * {@code SERVICE_} prefix, so these are the exact strings that reach
     * {@code @PreAuthorize}.
     *
     * <p>They are written out as literals rather than derived from
     * {@link LoyaltySessionIssuer#LOYALTY_SESSION_SCOPE}, because
     * {@code @PreAuthorize} takes a compile-time constant and
     * {@code .toUpperCase()} is not one. That is a real drift risk — renaming
     * the scope marker would leave this guard naming an authority nothing
     * grants, and the endpoint would 403 every customer — so
     * {@code LoyaltySessionControllerTest.authorityStringsMatchTheScopeMarkers}
     * derives both strings the long way and fails if they stop matching.
     */
    static final String OTP_AUTHORITY = "SERVICE_LOYALTY-OTP";
    static final String SESSION_AUTHORITY = "SERVICE_LOYALTY-SESSION";

    private final LoyaltySessionService sessions;

    public LoyaltySessionController(LoyaltySessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/exchange")
    @PreAuthorize("hasAuthority('" + OTP_AUTHORITY + "') or hasAuthority('" + SESSION_AUTHORITY + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Trade a live loyalty session for a renewable one",
            description = """
                    Call this ONCE, immediately after the OTP verify that minted the session. It \
                    returns a `refreshToken` alongside a fresh `loyaltyToken`; from then on the app \
                    renews with `POST /loyalty/session/refresh` and never needs another SMS while \
                    it keeps refreshing.

                    Requires a phone-scoped loyalty session bearer — the token ticketing's OTP \
                    verify returns. A staff or admin token is refused (403): a refresh chain is a \
                    customer credential, and the phone is taken from the caller's own token claim, \
                    never from the request.

                    The refresh token is shown ONCE and only its hash is stored, so it cannot be \
                    re-read from the server. Store it in the device keychain, not in application \
                    logs.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chain opened",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Session established",
                                      "data": {
                                        "phoneNumber": "+263771234567",
                                        "loyaltyToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3In0.sig",
                                        "expiresInSeconds": 43200,
                                        "refreshToken": "LRT-9tR2xQ1sK4mZ7pC0aB6vN3jH8dL5fG2yW1eU4oI0sA",
                                        "refreshExpiresInSeconds": 7776000
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "NO_PHONE_CLAIM — the token carries no phoneNumber",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "NO_PHONE_CLAIM",
                                      "message": "A loyalty session can only be exchanged by a token carrying a phoneNumber claim",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "Missing or expired bearer",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Invalid or missing token",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "403", description = "Not a phone-scoped loyalty session (e.g. a staff token)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - insufficient role or not the tenant owner",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> exchange() {
        String phone = CallerDetails.currentPhoneNumber();
        if (phone == null || phone.isBlank()) {
            // Belt-and-braces: JwtFilter only grants ROLE_CUSTOMER for a
            // phone-carrying token, but the scope marker alone does not imply a
            // phone claim, and a chain keyed on a null phone would be a session
            // that owns nothing and matches no ownership check.
            throw LoyaltyException.badRequest("NO_PHONE_CLAIM",
                    "A loyalty session can only be exchanged by a token carrying a phoneNumber claim");
        }
        LoyaltySessionService.Session session = sessions.start(phone, originScope());
        return ResponseEntity.ok(ApiResult.ok("Session established", body(session)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renew a loyalty session with a refresh token",
            description = """
                    Rotates the presented refresh token: it is retired, a successor is issued, and \
                    a fresh `loyaltyToken` comes back with it. **Replace your stored refresh token \
                    with the one in the response on every call** — the presented one stops working \
                    the moment this returns.

                    No `Authorization` header. The refresh token IS the credential, and the access \
                    token this call exists to replace has usually expired by the time it is made.

                    A `401` here is final: obtain a fresh phone proof (the OTP flow). Do not retry \
                    the same token — presenting an already-rotated token is treated as a stolen \
                    credential and ends every session in the chain, including the legitimate \
                    device's.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Renewed; the presented token is now spent",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Session refreshed",
                                      "data": {
                                        "phoneNumber": "+263771234567",
                                        "loyaltyToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3In0.sig2",
                                        "expiresInSeconds": 43200,
                                        "refreshToken": "LRT-K8vB2nQ5xW7pL0aR3cT6mH9jD1fS4yG2eU7oI5sZ",
                                        "refreshExpiresInSeconds": 7776000
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "MISSING_REFRESH_TOKEN — no token in the body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "MISSING_REFRESH_TOKEN",
                                      "message": "Please provide a refresh token.",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "SESSION_REFRESH_REJECTED — unknown, expired, revoked, "
                    + "already rotated (which revokes the whole chain), or the phone's registration was revoked. "
                    + "Deliberately opaque: the body never says which.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "SESSION_REFRESH_REJECTED",
                                      "message": "This session can no longer be renewed. Please sign in again.",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> refresh(
            @RequestBody(required = false) RefreshRequest body) {
        String presented = requireToken(body);
        LoyaltySessionService.Session session = sessions.refresh(presented);
        // The phone is NOT echoed from the request — there is none to echo. It
        // comes from the row the presented token resolved to, which is the only
        // thing that knows whose session this is. That is what makes it safe to
        // return: it is a fact the server looked up, not a claim the caller made.
        return ResponseEntity.ok(ApiResult.ok("Session refreshed", body(session)));
    }

    @PostMapping("/logout")
    @Operation(summary = "End the loyalty session chain",
            description = """
                    Revokes every refresh token in the presented token's chain, so neither this \
                    device nor a stolen copy can renew again. Call it on sign-out.

                    Always `200`, even for a token that is unknown or already dead — the outcome \
                    the caller wanted (this credential no longer works) is true either way, and \
                    answering otherwise would turn sign-out into an oracle for whether a token \
                    exists. The current `loyaltyToken` keeps working until its own TTL elapses; \
                    discard it client-side.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chain revoked, or was already dead",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Session ended",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "MISSING_REFRESH_TOKEN — no token in the body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "MISSING_REFRESH_TOKEN",
                                      "message": "Please provide a refresh token.",
                                      "data": null
                                    }""")))
    })
    public ResponseEntity<ApiResult<Void>> logout(@RequestBody(required = false) RefreshRequest body) {
        sessions.signOut(requireToken(body));
        return ResponseEntity.ok(ApiResult.ok("Session ended", null));
    }

    /** Body for {@code /refresh} and {@code /logout}. */
    public record RefreshRequest(
            @Schema(description = "The refresh token from the previous exchange/refresh response.",
                    example = "LRT-9tR2xQ1sK4mZ7pC0aB6vN3jH8dL5fG2yW1eU4oI0sA")
            String refreshToken) {}

    private static String requireToken(RefreshRequest body) {
        if (body == null || body.refreshToken() == null || body.refreshToken().isBlank()) {
            throw LoyaltyException.badRequest("MISSING_REFRESH_TOKEN", "Please provide a refresh token.");
        }
        return body.refreshToken();
    }

    /**
     * Which proof channel the caller's session came from, recorded on the chain
     * so it still names its origin after months of rotations.
     *
     * <p>Reads the granted authority rather than the raw claim because the
     * filter has already validated the token; the OTP marker is checked first
     * only because it is the live channel today. A caller reaching here always
     * holds one of the two — {@code @PreAuthorize} guarantees it — so the
     * fallback is unreachable and exists so the method is total.
     */
    private static String originScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority granted : auth.getAuthorities()) {
                if (OTP_AUTHORITY.equals(granted.getAuthority())) {
                    return "loyalty-otp";
                }
                if (SESSION_AUTHORITY.equals(granted.getAuthority())) {
                    return LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE;
                }
            }
        }
        return LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE;
    }

    private static Map<String, Object> body(LoyaltySessionService.Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phoneNumber", session.phoneNumber());
        data.put("loyaltyToken", session.accessToken());
        data.put("expiresInSeconds", session.expiresInSeconds());
        data.put("refreshToken", session.refreshToken());
        data.put("refreshExpiresInSeconds", session.refreshExpiresInSeconds());
        return data;
    }
}

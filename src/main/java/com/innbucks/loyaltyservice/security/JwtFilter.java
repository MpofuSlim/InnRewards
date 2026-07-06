package com.innbucks.loyaltyservice.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    /** MDC key for the customer's home-country routing tag, sourced from
     *  the {@code homeCountry} JWT claim. Distinct from {@code
     *  CountryMdcConfig.MDC_KEY} ("country"), which is the DEPLOYMENT pin. */
    public static final String HOME_COUNTRY_MDC_KEY = "homeCountry";

    private final JwtUtil jwtUtil;

    /** Shared cross-service logout denylist (Redis). Checked after the token
     *  passes signature/claim validation, so a logged-out token is rejected
     *  here fleet-wide instead of lingering until its short TTL expires. */
    private final RevokedTokenDenylist revokedTokenDenylist;

    /** Shared cross-service session-supersession store (Redis). Checked right
     *  after the denylist so a token superseded by a newer login / password
     *  change is rejected immediately instead of living out its TTL. */
    private final TokenVersionStore tokenVersionStore;

    /** This cell's country (INNBUCKS_COUNTRY). Compared against the JWT's
     *  {@code homeCountry} claim to spot wrong-cell requests. Non-final so
     *  Lombok's @RequiredArgsConstructor stays untouched — Spring sets it
     *  via field injection after construction. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/error",
            "/h2-console",
            "/actuator",
            "/loyalty/internal"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Authorization header — let the chain proceed. SecurityConfig decides
        // whether the path requires auth; missing-credential paths get their 401
        // from the entry point, not from us.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (!jwtUtil.isTokenValid(token)) {
                // Token is present but bad (expired, tampered signature, malformed).
                // Reject immediately so clients can distinguish "refresh me" from
                // "you forgot to send a token" — instead of slipping through
                // unauthenticated and hitting a downstream 401/403 with the same
                // generic message.
                writeUnauthorized(request, response, "INVALID_TOKEN", "Token is invalid or expired");
                return;
            }

            // Shared cross-service logout denylist. A signature/claim-valid
            // token the user has explicitly logged out is rejected here
            // (fail-open on any Redis trouble — see RevokedTokenDenylist).
            if (revokedTokenDenylist.isRevoked(token)) {
                log.warn("Rejected revoked (logged-out) token path={}", request.getRequestURI());
                writeUnauthorized(request, response, "TOKEN_REVOKED", "Token has been revoked");
                return;
            }

            // Cross-service session supersession (OWASP A07 / CWE-613).
            // user-service publishes the user's current token_version to the
            // shared Redis (auth:tokenver:<userUuid>) whenever a newer login /
            // password change supersedes older sessions. Reject a token whose
            // tokenVersion claim is strictly below that value, exactly like a
            // revoked token — otherwise it keeps working here until its short
            // TTL elapses. Fail-open: a legacy token with no userUuid /
            // tokenVersion claim, or a Redis blip (currentVersion == null),
            // passes through unchanged so a store outage never 401s everyone.
            UUID userUuid = jwtUtil.extractUserId(token);
            if (userUuid != null) {
                Long currentVersion = tokenVersionStore.currentVersion(userUuid.toString());
                if (currentVersion != null) {
                    Long tokenVersion = jwtUtil.extractTokenVersion(token);
                    if (tokenVersion != null && tokenVersion < currentVersion) {
                        log.warn("Rejected superseded token (tokenVersion={} < current={}) path={}",
                                tokenVersion, currentVersion, request.getRequestURI());
                        writeUnauthorized(request, response, "TOKEN_REVOKED", "Token has been revoked");
                        return;
                    }
                }
            }

            // mustChangePassword gate. user-service mints tokens with this
            // claim for accounts that haven't rotated their temp password
            // yet; every other service rejects those tokens until the
            // rotation happens.
            if (jwtUtil.extractMustChangePassword(token)) {
                writePasswordChangeRequired(response);
                return;
            }

            String email = jwtUtil.extractEmail(token);
            List<String> roles = jwtUtil.extractRoles(token);
            List<String> services = jwtUtil.extractServices(token);
            Integer tier = jwtUtil.extractTier(token);
            Boolean verified = jwtUtil.extractVerified(token);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
            for (String service : services) {
                if (service != null && !service.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SERVICE_" + service.toUpperCase()));
                }
            }
            if (tier != null) {
                for (int i = 1; i <= tier && i <= 4; i++) {
                    authorities.add(new SimpleGrantedAuthority("TIER_" + i));
                }
            }
            if (Boolean.TRUE.equals(verified)) {
                authorities.add(new SimpleGrantedAuthority("VERIFIED"));
            }

            UUID merchantId = jwtUtil.extractMerchantId(token);
            UUID shopId = jwtUtil.extractShopId(token);
            String phoneNumber = jwtUtil.extractPhoneNumber(token);
            UUID userId = jwtUtil.extractUserId(token);

            var auth = new UsernamePasswordAuthenticationToken(email, null, authorities);
            auth.setDetails(new CallerDetails(merchantId, shopId, phoneNumber, userId));
            SecurityContextHolder.getContext().setAuthentication(auth);
            // TRACE, not DEBUG: this line carries the subject (email = PII) plus
            // the caller's roles on every authenticated request. Keep it off by
            // default in every environment (prod log level is INFO; even a DEBUG
            // investigation shouldn't spill identity + authz into the logs).
            log.trace("JWT authenticated subject={} roles={} merchantId={} shopId={} path={}",
                    email, roles, merchantId, shopId, request.getRequestURI());
        } catch (Exception e) {
            // Defensive: if claim extraction blows up for any reason (corrupt
            // payload, etc.) treat it the same as an invalid token rather than
            // letting the request leak through unauthenticated.
            log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(request, response, "INVALID_TOKEN", "Token is invalid or expired");
            return;
        }

        // Push the customer's homeCountry into MDC for the downstream chain.
        // JwtUtil.extractHomeCountry returns null on any failure / missing
        // claim (legacy tokens, staff tokens), so we just skip the put in
        // those cases. Cleared in finally so request-thread recycling doesn't
        // leak it into the next request.
        String homeCountry = jwtUtil.extractHomeCountry(token);
        // Step 7 — wrong-cell defence in depth. A JWT minted by another cell
        // that somehow reached this one (misrouted client, stale base URL) is
        // rejected with 409 wrong_cell so the FE can switch base URL and retry.
        // The homeBaseUrl is left null here — only user-service holds the cell
        // registry; the FE either has the URL cached from /cells/lookup or
        // calls it again. Local-cell JWTs and legacy tokens with no
        // homeCountry claim pass through unchanged.
        if (homeCountry != null && !homeCountry.isBlank()
                && !homeCountry.equalsIgnoreCase(deploymentCountry)) {
            writeWrongCell(response, homeCountry);
            return;
        }

        boolean mdcSet = false;
        if (homeCountry != null && !homeCountry.isBlank()) {
            MDC.put(HOME_COUNTRY_MDC_KEY, homeCountry);
            mdcSet = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mdcSet) {
                MDC.remove(HOME_COUNTRY_MDC_KEY);
            }
        }
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}"
        );
    }

    /** 403 with errorCode=password_change_required — matches user-service's
     *  envelope so the FE branches on the same shape regardless of which
     *  service rejected the request. */
    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"403 FORBIDDEN\",\"message\":\"Password change required before " +
                "this account can use the rest of the app\",\"data\":{\"errorCode\":\"password_change_required\"}}"
        );
    }

    /** 409 wrong_cell when a JWT's homeCountry claim doesn't match this cell.
     *  Shape matches user-service's GlobalExceptionHandler envelope so the FE
     *  has one branch to handle. homeBaseUrl is null here — only user-service
     *  holds the cell registry; the FE either has it cached from /cells/lookup
     *  or calls it again. */
    private void writeWrongCell(HttpServletResponse response, String homeCountry) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"409 CONFLICT\",\"message\":\"Wrong cell \u2014 this request belongs to "
                        + homeCountry + "\",\"data\":{\"errorCode\":\"wrong_cell\",\"homeCountry\":\""
                        + homeCountry + "\",\"homeBaseUrl\":null}}"
        );
    }
}

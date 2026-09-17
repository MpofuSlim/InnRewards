package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Gates the {@code /loyalty/public/**} test surface behind a shared
 * {@code x-api-key} header, compared in constant time.
 *
 * <h2>Why a filter and not a check in the controller</h2>
 * {@code SecurityConfig} declares {@code /loyalty/public/**} {@code permitAll()},
 * so nothing in the Spring Security chain asks who is calling. A per-method
 * check in {@link com.innbucks.loyaltyservice.controller.PublicTestController}
 * would work, but it has to be remembered on every endpoint added later — and
 * the one that forgets is a live, unauthenticated spend of a customer's points.
 * A filter covers the prefix by shape, so a new mapping under it is gated the
 * moment it exists.
 *
 * <h2>This authenticates the APP, not the customer</h2>
 * The key ships in the client (the super app reads it from Firebase Remote
 * Config), so anyone who can read the app's config can read the key. It is
 * therefore a <b>throttle and a kill switch</b>, not access control: it stops
 * casual traffic and drive-by scanners, and it lets us revoke the whole surface
 * without an app release. It does NOT make the endpoints safe for production —
 * the phone number in the URL is still the only identity, so a holder of the key
 * can still spend any phone's points. {@code loyalty.public-test.enabled} staying
 * false on production is what keeps that off production; this key narrows who can
 * reach the surface on the cells that deliberately have it on.
 *
 * <h2>The gate is OPT-IN: no key configured means no gate</h2>
 * <ul>
 *   <li><b>Surface off</b> — this filter is inert. The controller answers 404
 *       ("this endpoint does not exist here"), and a 401 here would contradict
 *       that by confirming there is something behind the path.</li>
 *   <li><b>Surface on, no key configured</b> — the filter is <b>inert</b> and the
 *       endpoints answer exactly as they did before this class existed. See
 *       below for why that is not a silent downgrade.</li>
 *   <li><b>Surface on, key configured</b> — one opaque <b>401</b> for a missing
 *       key and a wrong key alike. The metric tag distinguishes them for us; the
 *       caller learns nothing either way.</li>
 * </ul>
 *
 * <p><b>This deliberately does NOT fail closed on a blank key</b>, and the
 * reason is that the switch above it already does. {@code public-test.enabled}
 * defaults to false and its whole documented meaning is "this cell serves an
 * unauthenticated surface where the phone in the URL is the identity" — an
 * operator who sets it has already accepted that anyone who can reach the cell
 * can spend any phone's points. A blank key therefore returns the surface to its
 * own documented baseline rather than weakening anything, and the gate switches
 * on the moment a key is provisioned. An earlier version refused every call with
 * a 503 instead, which made merging this class a breaking change for a client
 * that had never needed a key (operator's call, 2026-09-17).
 *
 * <p>What still fails closed is everything that matters: {@code enabled} itself,
 * and — once a key IS set — every call that does not present it.
 *
 * <p>{@code OPTIONS} is never gated: a CORS preflight carries no custom headers
 * by construction, so gating it would 401 the preflight and break the browser
 * call before the real request is ever sent.
 */
@Component
public class PublicTestApiKeyFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/loyalty/public";
    static final String API_KEY_HEADER = "x-api-key";

    private final boolean enabled;
    private final byte[] apiKey;
    private final LoyaltyMetrics metrics;

    public PublicTestApiKeyFilter(@Value("${loyalty.public-test.enabled:false}") boolean enabled,
                                  @Value("${loyalty.public-test.api-key:}") String apiKey,
                                  LoyaltyMetrics metrics) {
        this.enabled = enabled;
        String trimmed = apiKey == null ? "" : apiKey.trim();
        this.apiKey = trimmed.getBytes(StandardCharsets.UTF_8);
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        if (apiKey.length == 0) {
            // Opt-in: no key provisioned, no gate. Checked HERE rather than in
            // doFilterInternal so the request never enters this filter's body at
            // all — there is no refusal to accidentally reach, and no per-call
            // counter to drown the metric in on a cell that simply has no key.
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !covers(request.getRequestURI());
    }

    /**
     * Exactly the prefix, not anything that merely starts with its characters —
     * {@code /loyalty/publicity} is a different (authenticated) path and must not
     * be dragged under this gate, in either direction.
     */
    private static boolean covers(String uri) {
        return uri != null && (uri.equals(PATH_PREFIX) || uri.startsWith(PATH_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(API_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            metrics.incPublicTestRejected("missing_key");
            refuse(response, 401, "401 UNAUTHORIZED", "Invalid or missing API key");
            return;
        }
        // Constant-time compare — String.equals exits at the first differing
        // byte and leaks the key one byte at a time to a patient caller.
        if (!MessageDigest.isEqual(apiKey, presented.getBytes(StandardCharsets.UTF_8))) {
            metrics.incPublicTestRejected("bad_key");
            refuse(response, 401, "401 UNAUTHORIZED", "Invalid or missing API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** The {@code ApiResult} envelope every other refusal in this service uses. */
    private void refuse(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}

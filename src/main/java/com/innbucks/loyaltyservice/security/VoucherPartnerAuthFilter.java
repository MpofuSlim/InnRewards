package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.exception.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Authenticates the partner voucher API (V62): the three exact paths under
 * {@code /loyalty/partner/vouchers}, each a POST whose body is one compact JWS
 * signed by the partner ({@link PartnerRequestVerifier}).
 *
 * <p>Like {@link PublicTestApiKeyFilter} it covers its paths by SHAPE and refuses
 * outright: {@code SecurityConfig} permits these paths without a JWT, so this
 * filter is the only thing between the internet and a voucher generate. It runs
 * in the security chain, before any handler, and answers:
 * <ul>
 *   <li>the unknown-path 404 for every method while the API is switched off;</li>
 *   <li>415 when the body is not {@code application/jose}; 413 over 16 KB;</li>
 *   <li>one opaque 401 {@code PARTNER_AUTH_REJECTED} for every authentication
 *       failure, the reason in the log and {@code loyalty.partner.rejected};</li>
 *   <li>503 {@code PARTNER_REPLAY_CHECK_UNAVAILABLE} when the {@code jti} could
 *       not be recorded.</li>
 * </ul>
 * On success it puts the verified {@link PartnerRequest} on the request and lets
 * the call through. Every response from these paths is {@code no-store}.
 */
@Component
@Slf4j
public class VoucherPartnerAuthFilter extends OncePerRequestFilter {

    static final String PREFIX = "/loyalty/partner/vouchers/";
    static final Map<String, String> OPS = Map.of(
            PREFIX + "generate", "generate",
            PREFIX + "inquire", "inquire",
            PREFIX + "redeem", "redeem");
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final VoucherPartnerProperties props;
    private final PartnerRequestVerifier verifier;
    private final LoyaltyMetrics metrics;

    public VoucherPartnerAuthFilter(VoucherPartnerProperties props, PartnerRequestVerifier verifier,
                                    LoyaltyMetrics metrics) {
        this.props = props;
        this.verifier = verifier;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !OPS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId != null && REQUEST_ID.matcher(requestId).matches()) {
            response.setHeader("X-Request-Id", requestId);
        }
        if (!props.enabled()) {
            write(response, HttpServletResponse.SC_NOT_FOUND, "404 NOT_FOUND",
                    GlobalExceptionHandler.NOT_FOUND_MESSAGE);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (!"POST".equals(request.getMethod())) {
            // The handler's own 405 (with Allow) answers a wrong verb.
            chain.doFilter(request, response);
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/jose")) {
            write(response, 415, "415 UNSUPPORTED_MEDIA_TYPE",
                    "Send the signed request as Content-Type: application/jose.");
            return;
        }
        byte[] body = readAtMost(request.getInputStream(), MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            write(response, 413, "REQUEST_TOO_LARGE", "The request body is over 16 KB.");
            return;
        }
        String op = OPS.get(request.getRequestURI());
        InetAddress clientIp = IpLiterals.realIp(request).orElse(null);
        try {
            PartnerRequest verified = verifier.verify(new String(body, StandardCharsets.US_ASCII), op, clientIp);
            request.setAttribute(PartnerRequest.ATTRIBUTE, verified);
        } catch (PartnerAuthException rejected) {
            metrics.incPartnerRejected(rejected.reason());
            log.info("Partner request rejected op={} reason={} requestId={}", op, rejected.reason(), requestId);
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "PARTNER_AUTH_REJECTED", "Request rejected.");
            return;
        } catch (PartnerAuthException.ReplayCheckUnavailable unavailable) {
            metrics.incPartnerRejected("replay_check_unavailable");
            log.warn("Partner request refused: the jti could not be recorded op={} requestId={}", op, requestId,
                    unavailable.getCause());
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PARTNER_REPLAY_CHECK_UNAVAILABLE",
                    "Please retry: sign the request again with a new jti.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        return in.readNBytes(limit);
    }

    private static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}

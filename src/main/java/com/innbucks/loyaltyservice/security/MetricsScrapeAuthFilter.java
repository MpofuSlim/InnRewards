package com.innbucks.loyaltyservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates the Prometheus scraper on {@code /actuator/prometheus} via a
 * static shared token ({@code X-Metrics-Token} header, constant-time compare),
 * mirroring the fleet's X-Internal-Token pattern for S2S endpoints.
 *
 * <p>Why not a JWT: {@code /actuator/prometheus} sits behind
 * {@code .anyRequest().authenticated()}, and a scraper cannot renew a customer
 * JWT — a static token file in the Prometheus container would go stale at the
 * first expiry and every metric-based alert would silently go blind. Why not
 * {@code permitAll}: the gateway's api-docs proxy tunnel has historically made
 * "in-cluster only" an unsafe assumption for service-local paths, so metrics
 * (endpoint names, error rates, business volumes) stay behind an explicit
 * credential.
 *
 * <p>Fail-secure: with {@code monitoring.scrape-token} blank/unset (the
 * default), this filter authenticates nothing and the endpoint stays 401 —
 * enabling scraping is an explicit per-cell provisioning step
 * ({@code METRICS_SCRAPE_TOKEN}, see deploy/cells/cell.example.env). The
 * filter never rejects a request either — a wrong/absent header just falls
 * through to the JWT path, so behaviour for every other caller is unchanged.
 */
@Component
public class MetricsScrapeAuthFilter extends OncePerRequestFilter {

    private static final String SCRAPE_PATH = "/actuator/prometheus";
    private static final String TOKEN_HEADER = "X-Metrics-Token";

    private final byte[] scrapeToken;

    public MetricsScrapeAuthFilter(@Value("${monitoring.scrape-token:}") String scrapeToken) {
        String trimmed = scrapeToken == null ? "" : scrapeToken.trim();
        this.scrapeToken = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SCRAPE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        if (scrapeToken.length > 0
                && presented != null
                && MessageDigest.isEqual(scrapeToken, presented.getBytes(StandardCharsets.UTF_8))
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "metrics-scraper", null,
                    List.of(new SimpleGrantedAuthority("ROLE_METRICS_SCRAPE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}

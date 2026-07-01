package com.innbucks.loyaltyservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Logs every inbound HTTP request and its response (status + duration).
 * Sits just after CorrelationIdFilter so log lines carry the traceId MDC.
 * Skips noisy actuator/swagger paths.
 *
 * <p><b>Response body logging is OFF by default.</b> The loyalty domain
 * carries financial PII (phone numbers, voucher codes, balances) which
 * must not land in stdout. Flip {@code logging.access.bodies=true} only
 * in local dev when debugging a specific call — never in prod. When the
 * flag is off the filter skips response wrapping entirely (zero per-
 * request overhead).
 */
@Configuration
@Slf4j
public class HttpAccessLogFilter {

    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/actuator", "/swagger-ui", "/v3/api-docs", "/h2-console", "/favicon.ico"
    );

    /** Toggle response-body logging at runtime. Default OFF (prod-safe). */
    @Value("${logging.access.bodies:false}")
    private boolean logBodies;

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> httpAccessLogFilterRegistration() {
        final boolean bodies = this.logBodies;
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                if (skip(path)) {
                    chain.doFilter(request, response);
                    return;
                }
                long start = System.currentTimeMillis();
                String method = request.getMethod();
                String query = request.getQueryString();
                String fullPath = query == null ? path
                        : path + "?" + query.replaceAll(
                                "(?i)(msisdn|phoneNumber|phone|identifier|nationalId|national_id|pin|password|otp|token)=[^&]*",
                                "$1=***");
                log.info(">> {} {}", method, fullPath);

                if (!bodies) {
                    // Fast path: no response wrapping, no body materialisation.
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        long ms = System.currentTimeMillis() - start;
                        log.info("<< {} {} status={} duration={}ms",
                                method, fullPath, response.getStatus(), ms);
                    }
                    return;
                }

                // Debug-only branch: wrap the response so we can serialise the body
                // back into the log line. Costs an extra buffer per request — fine
                // for local debugging, not for production.
                ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
                try {
                    chain.doFilter(request, wrapped);
                } finally {
                    long ms = System.currentTimeMillis() - start;
                    byte[] body = wrapped.getContentAsByteArray();
                    String snippet = body.length == 0 ? "" :
                            new String(body, StandardCharsets.UTF_8);
                    if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
                    log.info("<< {} {} status={} duration={}ms{}",
                            method, fullPath, wrapped.getStatus(), ms,
                            snippet.isEmpty() ? "" : " body=" + snippet);
                    wrapped.copyBodyToResponse();
                }
            }
        });
        // Just after CorrelationIdFilter (HIGHEST_PRECEDENCE) so traceId is in MDC.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    private static boolean skip(String path) {
        for (String p : SKIP_PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }
}

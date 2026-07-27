package com.innbucks.loyaltyservice.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the scrape-auth contract (see MetricsScrapeAuthFilter):
 *  - fail-closed by default: a BLANK configured token must never authenticate
 *    anything, whatever the caller sends;
 *  - only an exact X-Metrics-Token match on exactly /actuator/prometheus
 *    authenticates, and only as the metrics role;
 *  - the filter never rejects: a mismatch falls through unauthenticated so the
 *    JWT path / 401 behaviour for every other caller is untouched;
 *  - an already-authenticated request (e.g. a real JWT) is never clobbered.
 *
 * Pure JUnit — no Spring context — mirroring the repo's contract-test style.
 */
class MetricsScrapeAuthFilterTest {

    private static final String TOKEN = "test-scrape-token-value";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest scrapeRequest(String presentedToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRequestURI("/actuator/prometheus");
        if (presentedToken != null) {
            request.addHeader("X-Metrics-Token", presentedToken);
        }
        return request;
    }

    private void run(MetricsScrapeAuthFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void blankConfiguredTokenAuthenticatesNothing() throws Exception {
        // The fail-closed default: an unprovisioned METRICS_SCRAPE_TOKEN must
        // not turn ANY header value (including empty) into an authentication.
        MetricsScrapeAuthFilter filter = new MetricsScrapeAuthFilter("");
        run(filter, scrapeRequest(""));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        run(filter, scrapeRequest("anything"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void matchingTokenAuthenticatesAsMetricsScraper() throws Exception {
        MetricsScrapeAuthFilter filter = new MetricsScrapeAuthFilter(TOKEN);
        run(filter, scrapeRequest(TOKEN));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("metrics-scraper");
        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_METRICS_SCRAPE");
    }

    @Test
    void wrongOrMissingTokenFallsThroughUnauthenticated() throws Exception {
        MetricsScrapeAuthFilter filter = new MetricsScrapeAuthFilter(TOKEN);
        run(filter, scrapeRequest("wrong-token"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        run(filter, scrapeRequest(null));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void otherPathsAreNotFiltered() throws Exception {
        // A valid token on any path other than the scrape endpoint must not
        // mint an authentication — the side door is exactly one path wide.
        MetricsScrapeAuthFilter filter = new MetricsScrapeAuthFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/loyalty/points/123");
        request.setRequestURI("/loyalty/points/123");
        request.addHeader("X-Metrics-Token", TOKEN);
        run(filter, request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void existingAuthenticationIsNeverClobbered() throws Exception {
        MetricsScrapeAuthFilter filter = new MetricsScrapeAuthFilter(TOKEN);
        Authentication existing = new UsernamePasswordAuthenticationToken(
                "real-user", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        run(filter, scrapeRequest(TOKEN));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    }
}

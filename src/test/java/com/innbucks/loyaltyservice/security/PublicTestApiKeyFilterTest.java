package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the api-key gate on {@code /loyalty/public/**} (see
 * {@link PublicTestApiKeyFilter}):
 * <ul>
 *   <li>the surface being OFF keeps the filter inert, so the path still answers
 *       the controller's 404 rather than a 401 that would confirm something is
 *       there;</li>
 *   <li>ON with a blank key fails CLOSED — 503 for everyone, and in particular
 *       a blank key is never matched by a blank header;</li>
 *   <li>ON with a key admits an exact match and refuses everything else with one
 *       opaque 401, never passing the request down the chain;</li>
 *   <li>the gate is exactly one prefix wide, and never covers a CORS preflight;</li>
 *   <li>every refusal is counted, tagged by a reason the caller never sees.</li>
 * </ul>
 *
 * Pure JUnit — no Spring context — mirroring the repo's contract-test style.
 */
class PublicTestApiKeyFilterTest {

    private static final String KEY = "test-public-api-key-value";

    private SimpleMeterRegistry registry;
    private LoyaltyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LoyaltyMetrics(registry);
    }

    private PublicTestApiKeyFilter filter(boolean enabled, String configuredKey) {
        return new PublicTestApiKeyFilter(enabled, configuredKey, metrics);
    }

    private MockHttpServletRequest request(String method, String uri, String presentedKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (presentedKey != null) {
            request.addHeader("x-api-key", presentedKey);
        }
        return request;
    }

    private MockHttpServletRequest publicRequest(String presentedKey) {
        return request("GET", "/loyalty/public/customers/%2B263771234567/wallet", presentedKey);
    }

    private record Run(MockHttpServletResponse response, boolean chainCalled) {}

    private Run run(PublicTestApiKeyFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Run(response, chain.getRequest() != null);
    }

    private double rejections(String reason) {
        var counter = registry.find("loyalty.public.test.rejected").tag("reason", reason).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void surfaceOffLeavesTheRequestAloneSoItStill404s() throws Exception {
        // Not a 401: the controller answers 404 when the switch is off, and a
        // 401 here would contradict it by confirming there is something behind
        // the path after all.
        Run run = run(filter(false, KEY), publicRequest(null));
        assertThat(run.chainCalled()).isTrue();
        assertThat(run.response().getStatus()).isEqualTo(200);
        assertThat(rejections("missing_key")).isZero();
    }

    @Test
    void enabledWithBlankKeyRefusesEverythingWith503() throws Exception {
        PublicTestApiKeyFilter filter = filter(true, "  ");

        Run noHeader = run(filter, publicRequest(null));
        assertThat(noHeader.chainCalled()).isFalse();
        assertThat(noHeader.response().getStatus()).isEqualTo(503);

        // The one that matters: a blank configured key must not be matchable by
        // a blank header, which would turn "unprovisioned" into "no key needed".
        Run blankHeader = run(filter, publicRequest(""));
        assertThat(blankHeader.chainCalled()).isFalse();
        assertThat(blankHeader.response().getStatus()).isEqualTo(503);

        Run someKey = run(filter, publicRequest(KEY));
        assertThat(someKey.chainCalled()).isFalse();
        assertThat(someKey.response().getStatus()).isEqualTo(503);

        assertThat(rejections("unconfigured")).isEqualTo(3d);
        assertThat(rejections("bad_key")).isZero();
    }

    @Test
    void matchingKeyPassesThrough() throws Exception {
        Run run = run(filter(true, KEY), publicRequest(KEY));
        assertThat(run.chainCalled()).isTrue();
        assertThat(run.response().getStatus()).isEqualTo(200);
        assertThat(rejections("bad_key")).isZero();
        assertThat(rejections("missing_key")).isZero();
    }

    @Test
    void wrongOrMissingKeyIsOneOpaque401AndNeverReachesTheHandler() throws Exception {
        PublicTestApiKeyFilter filter = filter(true, KEY);

        Run wrong = run(filter, publicRequest("not-the-key"));
        Run missing = run(filter, publicRequest(null));

        assertThat(wrong.chainCalled()).isFalse();
        assertThat(missing.chainCalled()).isFalse();
        assertThat(wrong.response().getStatus()).isEqualTo(401);
        assertThat(missing.response().getStatus()).isEqualTo(401);
        // Identical on the wire — the reason lives in the metric, not the body.
        assertThat(wrong.response().getContentAsString())
                .isEqualTo(missing.response().getContentAsString())
                .isEqualTo("{\"code\":\"401 UNAUTHORIZED\","
                        + "\"message\":\"Invalid or missing API key\",\"data\":null}");
        assertThat(wrong.response().getContentType()).startsWith("application/json");

        assertThat(rejections("bad_key")).isEqualTo(1d);
        assertThat(rejections("missing_key")).isEqualTo(1d);
    }

    @Test
    void theGateIsExactlyOnePrefixWide() throws Exception {
        PublicTestApiKeyFilter filter = filter(true, KEY);

        // An authenticated endpoint that merely starts with the same characters
        // must not be dragged under this gate.
        Run sibling = run(filter, request("GET", "/loyalty/publicity/123", null));
        assertThat(sibling.chainCalled()).isTrue();
        assertThat(sibling.response().getStatus()).isEqualTo(200);

        Run elsewhere = run(filter, request("GET", "/loyalty/points/123", null));
        assertThat(elsewhere.chainCalled()).isTrue();

        // ...and the prefix itself, with no trailing segment, IS covered.
        Run bare = run(filter, request("GET", "/loyalty/public", null));
        assertThat(bare.chainCalled()).isFalse();
        assertThat(bare.response().getStatus()).isEqualTo(401);
    }

    @Test
    void corsPreflightIsNeverGated() throws Exception {
        // A preflight carries no custom headers by construction, so gating it
        // would 401 the OPTIONS and the browser would never send the real call.
        Run run = run(filter(true, KEY), request("OPTIONS", "/loyalty/public/customers/x/wallet", null));
        assertThat(run.chainCalled()).isTrue();
        assertThat(run.response().getStatus()).isEqualTo(200);
        assertThat(rejections("missing_key")).isZero();
    }
}

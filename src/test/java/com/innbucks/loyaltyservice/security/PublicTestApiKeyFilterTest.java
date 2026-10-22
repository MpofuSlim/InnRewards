package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.exception.GlobalExceptionHandler;
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
 *   <li>the surface being OFF is the plain unknown-path 404 for every method,
 *       answered before the handler, so neither a 401 nor a binding error can
 *       confirm something is there;</li>
 *   <li>ON with a blank key leaves the surface UNGATED — the gate is opt-in, so
 *       merging it was not a breaking change for a client that never sent a key;</li>
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
    void surfaceOff_isTheUnknownPath404_forEveryMethodAndKey_beforeTheHandler() throws Exception {
        // Not a 401, which would confirm something is behind the path, and not
        // left to the controller, whose 404 comes only after Spring has bound the
        // body and path variables (an empty body was a 400 that named the fields).
        String unknownPath404 = "{\"code\":\"404 NOT_FOUND\","
                + "\"message\":\"" + GlobalExceptionHandler.NOT_FOUND_MESSAGE + "\",\"data\":null}";
        for (String configuredKey : new String[]{KEY, ""}) {
            PublicTestApiKeyFilter filter = filter(false, configuredKey);
            for (String method : new String[]{"GET", "POST", "PUT", "DELETE", "OPTIONS"}) {
                for (String presented : new String[]{null, KEY, "not-the-key"}) {
                    Run run = run(filter, request(method, "/loyalty/public/vouchers/redeem", presented));
                    assertThat(run.chainCalled()).as("%s key=%s", method, presented).isFalse();
                    assertThat(run.response().getStatus()).isEqualTo(404);
                    assertThat(run.response().getContentAsString()).isEqualTo(unknownPath404);
                    assertThat(run.response().getContentType()).startsWith("application/json");
                    // The unknown-path 404 carries no Cache-Control either.
                    assertThat(run.response().getHeader("Cache-Control")).isNull();
                }
            }
            Run bare = run(filter, request("GET", "/loyalty/public", null));
            assertThat(bare.response().getStatus()).isEqualTo(404);
        }
        // An off surface is not a refusal worth counting.
        assertThat(registry.find("loyalty.public.test.rejected").counters()).isEmpty();
    }

    @Test
    void surfaceOff_leavesEveryOtherPathAlone() throws Exception {
        PublicTestApiKeyFilter filter = filter(false, KEY);
        for (String uri : new String[]{"/loyalty/publicity/123", "/loyalty/points/123", "/loyalty/users/me"}) {
            Run run = run(filter, request("POST", uri, null));
            assertThat(run.chainCalled()).as(uri).isTrue();
            assertThat(run.response().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void enabledWithBlankKeyLeavesTheSurfaceUngated() throws Exception {
        // The gate is OPT-IN. A cell that has never configured a key gets the
        // behaviour it had before this filter existed — which is what the
        // `enabled` switch already promises on its own. Merging the filter must
        // not be a breaking change for a client that never needed a key.
        for (String configured : new String[]{"", "  ", null}) {
            PublicTestApiKeyFilter filter = filter(true, configured);

            Run noHeader = run(filter, publicRequest(null));
            assertThat(noHeader.chainCalled()).as("configured=%s", configured).isTrue();
            assertThat(noHeader.response().getStatus()).isEqualTo(200);

            // And a caller who DOES send something is not refused for it either.
            Run someKey = run(filter, publicRequest(KEY));
            assertThat(someKey.chainCalled()).isTrue();
            assertThat(someKey.response().getStatus()).isEqualTo(200);
        }

        // Nothing is counted: an ungated cell never enters the filter body, so
        // the meter stays a signal about real refusals rather than ordinary
        // traffic on a cell with no key.
        assertThat(registry.find("loyalty.public.test.rejected").counters()).isEmpty();
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

package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.entity.VoucherPartner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The filter in front of the partner voucher API (V62): what it answers before any handler runs. */
class VoucherPartnerAuthFilterTest {

    private final PartnerRequestVerifier verifier = mock(PartnerRequestVerifier.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private VoucherPartnerAuthFilter filter(boolean enabled) {
        VoucherPartnerProperties props = new VoucherPartnerProperties(enabled, "aud", null, null, null, null, null,
                null, null, null, null, null, null);
        return new VoucherPartnerAuthFilter(props, verifier, new LoyaltyMetrics(registry));
    }

    private record Run(MockHttpServletResponse response, MockHttpServletRequest request, boolean chainCalled) {}

    private Run run(VoucherPartnerAuthFilter f, String method, String uri, String contentType, byte[] body)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        if (contentType != null) {
            req.setContentType(contentType);
        }
        if (body != null) {
            req.setContent(body);
        }
        req.addHeader("X-Real-IP", "203.0.113.10");
        req.addHeader("X-Request-Id", "req-42");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return new Run(res, req, chain.getRequest() != null);
    }

    @Test
    void switchedOff_everyMethodIsTheUnknownPath404() throws Exception {
        for (String method : new String[]{"POST", "GET", "DELETE"}) {
            Run r = run(filter(false), method, "/loyalty/partner/vouchers/generate", "application/jose", "x.y.z".getBytes());
            assertThat(r.response().getStatus()).isEqualTo(404);
            assertThat(r.chainCalled()).isFalse();
        }
        verifyNoInteractions(verifier);
    }

    @Test
    void otherPathsAreLeftAlone() throws Exception {
        Run r = run(filter(true), "POST", "/loyalty/partner/registrations", "application/json", "{}".getBytes());
        assertThat(r.chainCalled()).isTrue();
        verifyNoInteractions(verifier);
    }

    @Test
    void theBodyMustBeAJose_andAtMost16Kb() throws Exception {
        Run json = run(filter(true), "POST", "/loyalty/partner/vouchers/redeem", "application/json", "{}".getBytes());
        assertThat(json.response().getStatus()).isEqualTo(415);
        assertThat(json.response().getHeader("Cache-Control")).isEqualTo("no-store");

        Run big = run(filter(true), "POST", "/loyalty/partner/vouchers/redeem", "application/jose",
                new byte[16 * 1024 + 1]);
        assertThat(big.response().getStatus()).isEqualTo(413);
        assertThat(big.response().getContentAsString()).contains("REQUEST_TOO_LARGE");
        verifyNoInteractions(verifier);
    }

    @Test
    void aRejectedRequestIsOneOpaque401_withTheReasonOnlyInTheMetric() throws Exception {
        when(verifier.verify(anyString(), eq("generate"), any())).thenThrow(new PartnerAuthException("bad_signature"));
        Run r = run(filter(true), "POST", "/loyalty/partner/vouchers/generate", "application/jose",
                "a.b.c".getBytes(StandardCharsets.US_ASCII));

        assertThat(r.response().getStatus()).isEqualTo(401);
        assertThat(r.response().getContentAsString()).isEqualTo(
                "{\"code\":\"PARTNER_AUTH_REJECTED\",\"message\":\"Request rejected.\",\"data\":null}");
        assertThat(r.response().getHeader("X-Request-Id")).isEqualTo("req-42");
        assertThat(r.chainCalled()).isFalse();
        assertThat(registry.find("loyalty.partner.rejected").tag("reason", "bad_signature").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void anUnanswerableReplayCheckIsA503() throws Exception {
        when(verifier.verify(anyString(), eq("inquire"), any()))
                .thenThrow(new PartnerAuthException.ReplayCheckUnavailable(null));
        Run r = run(filter(true), "POST", "/loyalty/partner/vouchers/inquire", "application/jose",
                "a.b.c".getBytes(StandardCharsets.US_ASCII));
        assertThat(r.response().getStatus()).isEqualTo(503);
        assertThat(r.response().getContentAsString()).contains("PARTNER_REPLAY_CHECK_UNAVAILABLE");
    }

    @Test
    void aVerifiedRequestReachesTheHandlerWithItsCaller() throws Exception {
        VoucherPartner partner = new VoucherPartner();
        PartnerRequest verified = new PartnerRequest(partner, "kid", "redeem", "jti-12345678", null);
        when(verifier.verify(eq("a.b.c"), eq("redeem"), any())).thenReturn(verified);
        Run r = run(filter(true), "POST", "/loyalty/partner/vouchers/redeem", "application/jose; charset=US-ASCII",
                "a.b.c".getBytes(StandardCharsets.US_ASCII));

        assertThat(r.chainCalled()).isTrue();
        assertThat(r.request().getAttribute(PartnerRequest.ATTRIBUTE)).isSameAs(verified);
        assertThat(r.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }
}

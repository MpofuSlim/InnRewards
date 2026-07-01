package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link UserServiceClient}'s {@code assignedMerchantIds()}
 * call against user-service's {@code GET /users/internal/merchants/assigned}.
 * Pins the wire shape (X-Internal-Token header, ApiResult envelope parsing)
 * and the not-silently-fall-back behaviour on a non-2xx — per the CLAUDE.md
 * cross-service-client mandate.
 *
 * <p>Pure JUnit + WireMock, no Spring context — we new up a {@code RestClient}
 * pointed at WireMock and reflectively set both the {@code restClient} and
 * {@code internalToken} fields, skipping the load-balanced builder (which only
 * adds Eureka resolution; the wire-level guarantees we care about are below
 * that layer).
 */
class UserServiceClientContractTest {

    private static WireMockServer wireMock;
    private static UserServiceClient client;

    @BeforeAll
    static void startAndWire() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Build the client with a no-op RestClient builder; we'll swap in the
        // real RestClient via reflection (the constructor's @LoadBalanced
        // builder isn't usable outside Spring).
        client = makeClient("the-shared-secret");
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static UserServiceClient makeClient(String token) {
        RestClient.Builder dummyBuilder = RestClient.builder();
        UserServiceClient c = new UserServiceClient(
                dummyBuilder, "http://localhost:" + wireMock.port(),
                500, 2000, token, new ObjectMapper());
        // Replace the load-balanced RestClient with a plain one pointed at
        // WireMock so requests actually go over the wire to our stubs.
        ReflectionTestUtils.setField(c, "restClient",
                RestClient.builder().baseUrl("http://localhost:" + wireMock.port()).build());
        return c;
    }

    @Test
    @DisplayName("happy path: parses ApiResult envelope into a Set<UUID>; sends X-Internal-Token")
    void assignedMerchantIds_happyPath_parsesEnvelopeAndSendsToken() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"Assigned merchant ids\","
                                + "\"data\":[\"" + a + "\",\"" + b + "\"]}")));

        Set<UUID> result = client.assignedMerchantIds();

        assertThat(result).containsExactlyInAnyOrder(a, b);
        wireMock.verify(getRequestedFor(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .withHeader("X-Internal-Token", equalTo("the-shared-secret")));
    }

    @Test
    @DisplayName("empty data array returns an empty set (no admins anywhere)")
    void assignedMerchantIds_emptyData_returnsEmpty() {
        wireMock.stubFor(get(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"Assigned merchant ids\",\"data\":[]}")));

        assertThat(client.assignedMerchantIds()).isEmpty();
    }

    @Test
    @DisplayName("ignores malformed UUID entries instead of failing the whole list")
    void assignedMerchantIds_malformedUuid_isSkipped() {
        UUID a = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"x\",\"data\":[\"" + a + "\",\"not-a-uuid\"]}")));

        assertThat(client.assignedMerchantIds()).containsExactly(a);
    }

    @Test
    @DisplayName("5xx from user-service: throws IllegalStateException (no silent fallback)")
    void assignedMerchantIds_5xx_throws() {
        wireMock.stubFor(get(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        assertThatThrownBy(() -> client.assignedMerchantIds())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user-service unavailable");
    }

    @Test
    @DisplayName("401 from user-service (wrong/missing internal token): throws IllegalStateException")
    void assignedMerchantIds_401_throws() {
        wireMock.stubFor(get(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.assignedMerchantIds())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("missing internal-api-token config: throws IllegalStateException without an HTTP call")
    void assignedMerchantIds_blankToken_throws_noHttp() {
        UserServiceClient noTokenClient = makeClient("");

        assertThatThrownBy(noTokenClient::assignedMerchantIds)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/users/internal/merchants/assigned?role=MERCHANT_ADMIN")));
    }
}

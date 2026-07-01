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

import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link UserServiceClient#getUserContact(UUID)} against
 * user-service's {@code GET /users/internal/{userUuid}/contact}. Pins the wire
 * shape (X-Internal-Token header, ApiResult envelope parsing into the loyalty
 * {@code UserContact} record) AND the best-effort behaviour the tenant-attach
 * notifier relies on: any non-2xx / unreachable user-service must return
 * {@link Optional#empty()} rather than throw, so a notification hiccup can never
 * fail the tenant-create 201.
 *
 * <p>Pure JUnit + WireMock, no Spring context — mirrors
 * {@link UserServiceClientContractTest}: new up a {@code RestClient} pointed at
 * WireMock and reflectively swap it in (the constructor's @LoadBalanced builder
 * isn't usable outside Spring; the wire-level guarantees live below that layer).
 */
class UserServiceClientContactContractTest {

    private static WireMockServer wireMock;
    private static UserServiceClient client;

    @BeforeAll
    static void startAndWire() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
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
        ReflectionTestUtils.setField(c, "restClient",
                RestClient.builder().baseUrl("http://localhost:" + wireMock.port()).build());
        return c;
    }

    @Test
    @DisplayName("happy path: parses phone/email/firstName; sends X-Internal-Token")
    void getUserContact_happyPath_parsesEnvelopeAndSendsToken() {
        UUID uuid = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/" + uuid + "/contact"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"User contact resolved\","
                                + "\"data\":{\"userUuid\":\"" + uuid + "\","
                                + "\"phoneNumber\":\"+263771234567\","
                                + "\"email\":\"alice@example.com\","
                                + "\"firstName\":\"Alice\"}}")));

        Optional<UserServiceClient.UserContact> result = client.getUserContact(uuid);

        assertThat(result).isPresent();
        assertThat(result.get().userUuid()).isEqualTo(uuid);
        assertThat(result.get().phoneNumber()).isEqualTo("+263771234567");
        assertThat(result.get().email()).isEqualTo("alice@example.com");
        assertThat(result.get().firstName()).isEqualTo("Alice");
        wireMock.verify(getRequestedFor(urlEqualTo("/users/internal/" + uuid + "/contact"))
                .withHeader("X-Internal-Token", equalTo("the-shared-secret")));
    }

    @Test
    @DisplayName("404 (unknown user): returns empty, does not throw")
    void getUserContact_404_returnsEmpty() {
        UUID uuid = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/" + uuid + "/contact"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"404 NOT_FOUND\",\"message\":\"User not found\",\"data\":null}")));

        assertThat(client.getUserContact(uuid)).isEmpty();
    }

    @Test
    @DisplayName("401 (wrong/missing internal token): returns empty, does not throw")
    void getUserContact_401_returnsEmpty() {
        UUID uuid = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/users/internal/" + uuid + "/contact"))
                .willReturn(aResponse().withStatus(401)));

        assertThat(client.getUserContact(uuid)).isEmpty();
    }

    @Test
    @DisplayName("user-service unreachable (connection refused): returns empty, does not throw")
    void getUserContact_unreachable_returnsEmpty() throws Exception {
        // Point a SEPARATE client at a known-closed port so any connect attempt
        // is refused. Keeps the shared WireMock untouched for the rest of the
        // suite (a second WireMock start would grab a different dynamic port).
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        UserServiceClient dead = new UserServiceClient(
                RestClient.builder(), "http://localhost:" + closedPort,
                500, 500, "the-shared-secret", new ObjectMapper());
        ReflectionTestUtils.setField(dead, "restClient",
                RestClient.builder().baseUrl("http://localhost:" + closedPort).build());

        assertThat(dead.getUserContact(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("blank internal-api-token: returns empty without an HTTP call")
    void getUserContact_blankToken_returnsEmpty_noHttp() {
        UserServiceClient noTokenClient = makeClient("");
        UUID uuid = UUID.randomUUID();

        assertThat(noTokenClient.getUserContact(uuid)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlEqualTo("/users/internal/" + uuid + "/contact")));
    }

    @Test
    @DisplayName("null userUuid: returns empty without an HTTP call")
    void getUserContact_nullUuid_returnsEmpty_noHttp() {
        assertThat(client.getUserContact(null)).isEmpty();
        wireMock.verify(0, getRequestedFor(urlMatching("/users/internal/.*/contact")));
    }
}

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
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link UserServiceClient#merchantStaffPhones(UUID)}
 * against user-service's
 * {@code GET /users/internal/shop-staff/by-merchant/{merchantId}/contacts}.
 *
 * <p>Pins the wire shape (X-Internal-Token header, ApiResult envelope into
 * {@code StaffContact} rows, null-phone rows filtered, whitespace stripped)
 * AND the fail-open contract the STAFF_RECIPIENT guard depends on: the
 * Optional must distinguish an AUTHORITATIVE empty staff list
 * ({@code Optional.of(empty)}) from an UNKNOWN answer
 * ({@code Optional.empty()}) — and every failure mode (non-2xx, malformed
 * body, connect-refused, unconfigured token) lands on UNKNOWN without
 * throwing, because this sits under the earn path.
 *
 * <p>Pure JUnit + WireMock, no Spring context — mirrors
 * {@link UserServiceClientContactContractTest}, including the reflective
 * RestClient swap (the @LoadBalanced builder isn't usable outside Spring) and
 * the separate closed-port client for the fault case (never stop/restart the
 * shared server — the second start gets a different port).
 */
class UserServiceClientStaffContractTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String PATH =
            "/users/internal/shop-staff/by-merchant/" + MERCHANT + "/contacts";

    private static WireMockServer wireMock;
    private static UserServiceClient client;

    @BeforeAll
    static void startAndWire() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = makeClient("the-shared-secret", "http://localhost:" + wireMock.port());
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static UserServiceClient makeClient(String token, String baseUrl) {
        UserServiceClient c = new UserServiceClient(
                RestClient.builder(), baseUrl, 500, 2000, token, new ObjectMapper());
        ReflectionTestUtils.setField(c, "restClient",
                RestClient.builder().baseUrl(baseUrl).build());
        return c;
    }

    @Test
    @DisplayName("happy path: parses phones, filters null-phone rows, strips whitespace, sends the token")
    void happyPath() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "code": "200 OK",
                          "message": "Merchant staff contacts resolved",
                          "data": [
                            {"userUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "phoneNumber": "+263 771 234 567"},
                            {"userUuid": "8d0f7780-8536-51ef-a55c-f18fd2091bf8", "phoneNumber": null},
                            {"userUuid": "9e1a8891-9647-62f0-b66d-0a90e31a2c09", "phoneNumber": "+263779999999"}
                          ]
                        }
                        """)));

        Optional<Set<String>> result = client.merchantStaffPhones(MERCHANT);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactlyInAnyOrder("+263771234567", "+263779999999");
        // Outbound wire contract: the shared secret rides the header.
        wireMock.verify(getRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo("the-shared-secret")));
    }

    @Test
    @DisplayName("empty data array is AUTHORITATIVE no-staff — Optional.of(empty), not empty Optional")
    void authoritativeEmpty() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"200 OK\",\"message\":\"Merchant staff contacts resolved\",\"data\":[]}")));

        Optional<Set<String>> result = client.merchantStaffPhones(MERCHANT);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    @DisplayName("non-2xx (the endpoint's 401) is UNKNOWN — empty Optional, no throw")
    void non2xxIsUnknown() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(client.merchantStaffPhones(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("malformed body is UNKNOWN — empty Optional, no throw")
    void malformedBodyIsUnknown() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("<html>not json</html>")));

        assertThat(client.merchantStaffPhones(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("connect-refused is UNKNOWN — empty Optional, no throw")
    void connectRefusedIsUnknown() {
        // Separate client at a known-closed port; the shared server keeps its
        // port for the other cases.
        UserServiceClient dead = makeClient("the-shared-secret", "http://localhost:1");

        assertThat(dead.merchantStaffPhones(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("guard rails: unconfigured token and null merchant never touch the network")
    void guardRailsNeverHitTheNetwork() {
        UserServiceClient tokenless = makeClient("", "http://localhost:" + wireMock.port());

        assertThat(tokenless.merchantStaffPhones(MERCHANT)).isEmpty();
        assertThat(client.merchantStaffPhones(null)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/users/internal/.*")));
    }
}

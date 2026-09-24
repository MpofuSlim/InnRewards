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

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link UserServiceClient#organizationAdminEmails} against
 * user-service's {@code GET /users/internal/organizations/{id}/admins} — who
 * receives a merchant's invoice. Pins the wire shape (X-Internal-Token header,
 * ApiResult envelope with {@code data:[{userUuid, email}]}) and the
 * best-effort contract: every failure is an empty list, never an exception,
 * because the only caller is an after-commit mailer. Per the CLAUDE.md
 * cross-service-client mandate. The stubs transcribe user-service's
 * {@code InternalOrganizationController} (ticketing-system #615).
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

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final String ADMINS_PATH = "/users/internal/organizations/" + ORG + "/admins";

    @Test
    @DisplayName("organization admins: parses the ApiResult envelope into emails; sends X-Internal-Token")
    void organizationAdminEmails_happyPath_parsesEnvelopeAndSendsToken() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"Organization admins\",\"data\":["
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":\"rudo@chikwanha-traders.co.zw\"},"
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":\"tendai@chikwanha-traders.co.zw\"}]}")));

        assertThat(client.organizationAdminEmails(ORG))
                .containsExactly("rudo@chikwanha-traders.co.zw", "tendai@chikwanha-traders.co.zw");
        wireMock.verify(getRequestedFor(urlEqualTo(ADMINS_PATH))
                .withHeader("X-Internal-Token", equalTo("the-shared-secret")));
    }

    @Test
    @DisplayName("organization admins: a phone-only admin (null/blank email) is skipped, duplicates collapse")
    void organizationAdminEmails_blankAndDuplicateEmails_areDropped() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"x\",\"data\":["
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":null},"
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":\"  \"},"
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":\"rudo@chikwanha-traders.co.zw\"},"
                                + "{\"userUuid\":\"" + UUID.randomUUID() + "\",\"email\":\"rudo@chikwanha-traders.co.zw\"}]}")));

        assertThat(client.organizationAdminEmails(ORG)).containsExactly("rudo@chikwanha-traders.co.zw");
    }

    @Test
    @DisplayName("organization admins: an empty data array is an empty list (nobody to email)")
    void organizationAdminEmails_emptyData_isEmpty() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"Organization admins\",\"data\":[]}")));

        assertThat(client.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: 401 (token rejected) is an empty list, never an exception")
    void organizationAdminEmails_401_isEmpty() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(client.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: 404 (a user-service too old to serve it) is an empty list")
    void organizationAdminEmails_404_isEmpty() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: 5xx is an empty list — the invoice exists regardless")
    void organizationAdminEmails_5xx_isEmpty() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        assertThat(client.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: a non-JSON 200 body is an empty list")
    void organizationAdminEmails_garbageBody_isEmpty() {
        wireMock.stubFor(get(urlEqualTo(ADMINS_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html>Request Rejected</html>")));

        assertThat(client.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: connection refused is an empty list")
    void organizationAdminEmails_connectRefused_isEmpty() {
        // A separate client at a known-closed port; never stop/restart the
        // shared WireMock, whose second start gets a different dynamic port.
        UserServiceClient offline = makeClient("the-shared-secret");
        ReflectionTestUtils.setField(offline, "restClient",
                RestClient.builder().baseUrl("http://localhost:1").build());

        assertThat(offline.organizationAdminEmails(ORG)).isEmpty();
    }

    @Test
    @DisplayName("organization admins: a null organization or an unconfigured token never hits the wire")
    void organizationAdminEmails_guardRails_noHttp() {
        assertThat(client.organizationAdminEmails(null)).isEmpty();
        assertThat(makeClient("").organizationAdminEmails(ORG)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/users/internal/organizations/.*")));
    }
}

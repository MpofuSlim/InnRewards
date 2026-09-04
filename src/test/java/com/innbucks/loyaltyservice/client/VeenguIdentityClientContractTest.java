package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link VeenguIdentityClient} against Veengu's
 * {@code GET /auth/identity} (Frontend API v3.1.0, pinned in ticketing-system's
 * {@code docs/api/veengu-openapi.json}).
 *
 * <p>What must never drift, one test each:
 * <ul>
 *   <li>the OUTBOUND wire shape — GET, {@code v-tenant} + {@code v-access-token}
 *       headers, nothing else pretending to be auth;</li>
 *   <li>the three-way outcome mapping, and above all which side of the
 *       Rejected/Unavailable line each response lands on — a 5xx or WAF page
 *       classified as Rejected refuses every customer for as long as the
 *       outage lasts, which is the EcoCash edge lesson replayed;</li>
 *   <li>the guard rails: a blank token and an unconfigured client must never
 *       touch the network.</li>
 * </ul>
 *
 * <p>Pure JUnit + WireMock, no Spring context. The connect-refused case points
 * a separate client at a closed port rather than stopping the shared server —
 * a restart would grab a different dynamic port and break the other tests.
 */
class VeenguIdentityClientContractTest {

    private static final String TENANT = "innbucks";
    private static final String TOKEN = "veengu-session-token-abc123";

    private static WireMockServer wireMock;
    private static VeenguIdentityClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = new VeenguIdentityClient(
                "http://localhost:" + wireMock.port(), TENANT, 500, 2000, new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("happy 200: phone extracted from Veengu's answer; v-tenant + v-access-token sent")
    void identify_happyPath() {
        // Body shape straight from the pinned spec's `identity` schema —
        // phoneNumber is E.164 with the leading '+'.
        wireMock.stubFor(get(urlEqualTo("/auth/identity"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"emailAddress":"kate@example.com","username":"kate@example.com",
                                 "hasPassword":true,"name":"Kate Maison",
                                 "phoneNumber":"+263771234567"}""")));

        VeenguIdentityClient.IdentityOutcome outcome = client.identify(TOKEN);

        assertThat(outcome).isEqualTo(new VeenguIdentityClient.Verified("+263771234567"));
        wireMock.verify(getRequestedFor(urlEqualTo("/auth/identity"))
                .withHeader("v-tenant", equalTo(TENANT))
                .withHeader("v-access-token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("404 'Identity not found' (the documented refusal) is Rejected")
    void identify_404_isRejected() {
        wireMock.stubFor(get(urlEqualTo("/auth/identity")).willReturn(aResponse().withStatus(404)));

        assertThat(client.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Rejected.class);
    }

    @Test
    @DisplayName("401 (expired/invalid session) is Rejected")
    void identify_401_isRejected() {
        wireMock.stubFor(get(urlEqualTo("/auth/identity")).willReturn(aResponse().withStatus(401)));

        assertThat(client.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Rejected.class);
    }

    @Test
    @DisplayName("a 200 identity WITHOUT a phone number is Rejected, never Verified")
    void identify_identityWithNoPhone_isRejected() {
        // A real Veengu identity that carries no phone cannot prove ownership
        // of one. Registering a blank/absent phone here would be minting a
        // registration out of nothing.
        wireMock.stubFor(get(urlEqualTo("/auth/identity"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"username\":\"kate@example.com\",\"hasPassword\":true}")));

        assertThat(client.identify(TOKEN)).isEqualTo(new VeenguIdentityClient.Rejected("no_phone"));
    }

    @Test
    @DisplayName("5xx is Unavailable — Veengu being down is not a verdict on the token")
    void identify_500_isUnavailable() {
        wireMock.stubFor(get(urlEqualTo("/auth/identity")).willReturn(aResponse().withStatus(500)));

        assertThat(client.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Unavailable.class);
    }

    @Test
    @DisplayName("an unexpected 400 is Unavailable — a malformed request is OUR bug, not the customer's token")
    void identify_400_isUnavailable() {
        wireMock.stubFor(get(urlEqualTo("/auth/identity")).willReturn(aResponse().withStatus(400)));

        assertThat(client.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Unavailable.class);
    }

    @Test
    @DisplayName("a 2xx whose body is not JSON (WAF block page) is Unavailable, never Rejected")
    void identify_htmlBlockPage_isUnavailable() {
        // The EcoCash edge lesson: bot-mitigation layers serve their block page
        // with HTTP 200 and text/html. Classifying it as Rejected would refuse
        // every customer for as long as the block lasts.
        wireMock.stubFor(get(urlEqualTo("/auth/identity"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Request Rejected. Support ID: 1234</body></html>")));

        assertThat(client.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Unavailable.class);
    }

    @Test
    @DisplayName("connect-refused is Unavailable")
    void identify_connectRefused_isUnavailable() {
        // Separate client at a port nothing listens on — never stop/restart the
        // shared WireMock, its dynamic port would change under the other tests.
        VeenguIdentityClient dead = new VeenguIdentityClient(
                "http://localhost:1", TENANT, 300, 300, new ObjectMapper());

        assertThat(dead.identify(TOKEN)).isInstanceOf(VeenguIdentityClient.Unavailable.class);
    }

    @Test
    @DisplayName("a blank token never touches the network and is Rejected")
    void identify_blankToken_neverCallsVeengu() {
        assertThat(client.identify("  ")).isEqualTo(new VeenguIdentityClient.Rejected("blank_token"));
        assertThat(client.identify(null)).isEqualTo(new VeenguIdentityClient.Rejected("blank_token"));

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("an unconfigured client is Unavailable and never calls out")
    void identify_unconfigured_isUnavailableWithoutNetwork() {
        VeenguIdentityClient unconfigured = new VeenguIdentityClient(
                "", "", 300, 300, new ObjectMapper());

        assertThat(unconfigured.identify(TOKEN))
                .isEqualTo(new VeenguIdentityClient.Unavailable("unconfigured"));
        assertThat(unconfigured.isConfigured()).isFalse();
        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("configured = base URL AND tenant code, not either alone")
    void isConfigured_needsBoth() {
        assertThat(new VeenguIdentityClient("http://localhost:1", "", 300, 300, new ObjectMapper())
                .isConfigured()).isFalse();
        assertThat(new VeenguIdentityClient("", TENANT, 300, 300, new ObjectMapper())
                .isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
    }
}

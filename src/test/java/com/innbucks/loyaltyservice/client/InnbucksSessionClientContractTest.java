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
 * Contract test for {@link InnbucksSessionClient} against the InnBucks Client
 * Service msisdn-scoped account endpoint. Response shapes are taken from the
 * partner's own Postman collections and the samples they supplied.
 *
 * <p>The cases that carry the security weight:
 * <ul>
 *   <li><b>A 200 with a failure responseCode is a REFUSAL.</b> The platform
 *       answers business failures with HTTP 200, so a client that read a bare
 *       2xx as proof would accept exactly the cross-customer refusal this mode
 *       exists to detect — a customer could register any number they typed.</li>
 *   <li><b>The probe carries the CUSTOMER's token, not the app's.</b> The
 *       outbound assertion pins that: the whole distinction between this and
 *       the /validate endpoint (which proves only that a number exists) is
 *       which credential authorizes the call.</li>
 *   <li><b>No answer is never a verdict.</b> 5xx, connect-refused and a WAF
 *       block page served as 200/text-html must be Unavailable, never Rejected.</li>
 * </ul>
 */
class InnbucksSessionClientContractTest {

    private static final String API_KEY = "the-platform-api-key";
    private static final String USER_TOKEN = "customer-user-token-abc123";
    private static final String E164 = "+263777224008";
    private static final String PROBE = "/api/v1/account/msisdn/263777224008/details?currency=USD";

    private static WireMockServer wireMock;
    private static InnbucksSessionClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = newClient("http://localhost:" + wireMock.port());
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static InnbucksSessionClient newClient(String baseUrl) {
        return new InnbucksSessionClient(
                baseUrl, API_KEY,
                "/api/v1/account/msisdn/{msisdn}/details?currency=USD",
                "00,000,0", 500, 2000, new ObjectMapper());
    }

    @Test
    @DisplayName("200 + success code: ownership proved, and the CUSTOMER's token authorized the probe")
    void verify_happyPath() {
        // Body shape as supplied by the partner for this endpoint.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"responseCode":"000","responseDescription":"Approved or completed successfully",
                                 "firstName":"SEDRICK","lastName":"NYANYIWA",
                                 "accounts":[{"accountNumber":"3005243923335","currency":"ZWG"}]}""")));

        InnbucksSessionClient.OwnershipOutcome outcome = client.verifyOwnership(USER_TOKEN, E164);

        assertThat(outcome).isEqualTo(new InnbucksSessionClient.Verified("000"));
        // The bearer MUST be the customer's user token. If this ever became the
        // app's client-service token, the probe would prove only that the number
        // exists — the /validate trap, reintroduced through the back door.
        wireMock.verify(getRequestedFor(urlEqualTo(PROBE))
                .withHeader("Authorization", equalTo("Bearer " + USER_TOKEN))
                .withHeader("X-Api-Key", equalTo(API_KEY)));
    }

    @Test
    @DisplayName("the stored '+' is stripped to the platform's bare msisdn format")
    void verify_stripsPlusForTheWire() {
        // loyalty stores +263777224008; the platform's format is 263777224008.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"000\"}")));

        client.verifyOwnership(USER_TOKEN, E164);

        wireMock.verify(getRequestedFor(urlEqualTo(PROBE)));
        wireMock.verify(0, getRequestedFor(urlMatching(".*\\+263.*")));
    }

    @Test
    @DisplayName("a numeric responseCode 0 also counts as success")
    void verify_numericSuccessCode() {
        // Some endpoints on this platform return responseCode as a NUMBER.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":0,\"responseMsg\":\"Success\"}")));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isInstanceOf(InnbucksSessionClient.Verified.class);
    }

    @Test
    @DisplayName("SECURITY: a 200 carrying a FAILURE code is a refusal, never a pass")
    void verify_2xxWithFailureCode_isRejected() {
        // This is the case the whole mode turns on. The platform reports business
        // failures with HTTP 200 + a non-success code, so reading a bare 2xx as
        // proof would let a customer register any number they typed.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"02\",\"responseMessage\":\"Validation failed\"}")));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("code_02"));
    }

    @Test
    @DisplayName("403 (token not bound to this msisdn) is Rejected")
    void verify_403_isRejected() {
        wireMock.stubFor(get(urlEqualTo(PROBE)).willReturn(aResponse().withStatus(403)));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("http_403"));
    }

    @Test
    @DisplayName("401 (expired/invalid user token) is Rejected")
    void verify_401_isRejected() {
        wireMock.stubFor(get(urlEqualTo(PROBE)).willReturn(aResponse().withStatus(401)));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("http_401"));
    }

    @Test
    @DisplayName("404 (no such account for this msisdn) is Rejected")
    void verify_404_isRejected() {
        wireMock.stubFor(get(urlEqualTo(PROBE)).willReturn(aResponse().withStatus(404)));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("http_404"));
    }

    @Test
    @DisplayName("5xx is Unavailable — the middleware being down is not a verdict on the claim")
    void verify_500_isUnavailable() {
        wireMock.stubFor(get(urlEqualTo(PROBE)).willReturn(aResponse().withStatus(500)));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isInstanceOf(InnbucksSessionClient.Unavailable.class);
    }

    @Test
    @DisplayName("an unexpected 400 is Unavailable — a malformed request is OUR bug, not the caller's claim")
    void verify_400_isUnavailable() {
        wireMock.stubFor(get(urlEqualTo(PROBE)).willReturn(aResponse().withStatus(400)));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Unavailable("http_400"));
    }

    @Test
    @DisplayName("a 2xx WAF block page (200 + text/html) is Unavailable, never Rejected")
    void verify_htmlBlockPage_isUnavailable() {
        // The EcoCash edge lesson: bot-mitigation serves its block page with
        // HTTP 200 and text/html. Calling that a refusal would tell every
        // customer they were rejected for as long as the block lasted.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Request Rejected. Support ID: 1234</body></html>")));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Unavailable("malformed_2xx"));
    }

    @Test
    @DisplayName("a 2xx JSON object with NO responseCode is Unavailable, not a pass")
    void verify_2xxWithoutResponseCode_isUnavailable() {
        // Absence of the field is not success. Defaulting to proven here would
        // register on any JSON the middleware happened to return.
        wireMock.stubFor(get(urlEqualTo(PROBE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"firstName\":\"SEDRICK\"}")));

        assertThat(client.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Unavailable("no_response_code"));
    }

    @Test
    @DisplayName("connect-refused is Unavailable")
    void verify_connectRefused_isUnavailable() {
        // Separate client at a closed port — never stop/restart the shared
        // WireMock, its dynamic port would change under the other tests.
        InnbucksSessionClient dead = newClient("http://localhost:1");

        assertThat(dead.verifyOwnership(USER_TOKEN, E164))
                .isInstanceOf(InnbucksSessionClient.Unavailable.class);
    }

    @Test
    @DisplayName("a blank token or blank phone never touches the network")
    void verify_blankInputs_neverCallOut() {
        assertThat(client.verifyOwnership("  ", E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("blank_token"));
        assertThat(client.verifyOwnership(null, E164))
                .isEqualTo(new InnbucksSessionClient.Rejected("blank_token"));
        assertThat(client.verifyOwnership(USER_TOKEN, " "))
                .isEqualTo(new InnbucksSessionClient.Rejected("blank_phone"));

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("an unconfigured client is Unavailable and never calls out")
    void verify_unconfigured_isUnavailable() {
        InnbucksSessionClient unconfigured = new InnbucksSessionClient(
                "", "", "", "00", 300, 300, new ObjectMapper());

        assertThat(unconfigured.verifyOwnership(USER_TOKEN, E164))
                .isEqualTo(new InnbucksSessionClient.Unavailable("unconfigured"));
        assertThat(unconfigured.isConfigured()).isFalse();
        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("configured = base URL AND api key AND probe path")
    void isConfigured_needsAllThree() {
        ObjectMapper m = new ObjectMapper();
        String path = "/api/v1/account/msisdn/{msisdn}/details";
        assertThat(new InnbucksSessionClient("http://x", "", path, "00", 300, 300, m).isConfigured()).isFalse();
        assertThat(new InnbucksSessionClient("", API_KEY, path, "00", 300, 300, m).isConfigured()).isFalse();
        assertThat(new InnbucksSessionClient("http://x", API_KEY, "", "00", 300, 300, m).isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
    }
}

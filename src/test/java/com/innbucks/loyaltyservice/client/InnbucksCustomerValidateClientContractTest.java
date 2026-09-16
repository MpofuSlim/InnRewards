package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link InnbucksCustomerValidateClient} against the InnBucks
 * Client Service customer-directory endpoint
 * ({@code GET /auth/client-service/msisdn/{msisdn}/validate}) and the platform
 * login that authorizes it ({@code POST /auth/third-party}).
 *
 * <p>The cases that carry the weight:
 * <ul>
 *   <li><b>A 200 with a failure responseCode is NOT a customer.</b> The
 *       platform reports business failures with HTTP 200; reading a bare 2xx
 *       as confirmation would register every phone anyone typed.</li>
 *   <li><b>Our own credentials being refused is an OUTAGE, never a verdict.</b>
 *       Expired platform creds must not read as "none of your customers
 *       exist" — after one forced token refresh, a second 401 maps to
 *       Unavailable.</li>
 *   <li><b>No answer is never a verdict.</b> 5xx, connect-refused and a WAF
 *       block page served as 200/text-html are Unavailable, never
 *       NotACustomer.</li>
 * </ul>
 */
class InnbucksCustomerValidateClientContractTest {

    private static final String API_KEY = "the-platform-api-key";
    private static final String E164 = "+263782606983";
    private static final String LOGIN = "/auth/third-party";
    private static final String VALIDATE = "/auth/client-service/msisdn/263782606983/validate";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    /**
     * A FRESH client per test: the bearer is cached inside the instance until
     * its exp/TTL, so a shared client would let one test's token satisfy the
     * next test's login expectations.
     */
    private static InnbucksCustomerValidateClient newClient(String baseUrl) {
        return new InnbucksCustomerValidateClient(
                baseUrl, API_KEY, "svc-user", "svc-pass",
                LOGIN, "/auth/client-service/msisdn/{msisdn}/validate",
                "00,000,0", 480, 500, 2000, new ObjectMapper());
    }

    private InnbucksCustomerValidateClient client() {
        return newClient("http://localhost:" + wireMock.port());
    }

    private void stubLogin(String token) {
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"" + token + "\"}")));
    }

    @Test
    @DisplayName("200 + success code: a real customer — and the wire contract on both calls holds")
    void check_happyPath() {
        stubLogin("tok-1");
        // Body shape as measured on staging for this endpoint.
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"responseCode":"00","responseMessage":"Validation successful",
                                 "firstName":"Tawanda","lastName":"Mpofu",
                                 "tandCAccepted":true,"pinSet":true}""")));

        InnbucksCustomerValidateClient.CustomerCheckOutcome outcome = client().checkCustomer(E164);

        assertThat(outcome).isEqualTo(new InnbucksCustomerValidateClient.Customer("00"));
        // Outbound login contract: X-Api-Key + the credentials as JSON fields.
        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("svc-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("svc-pass"))));
        // The validate call rides the APP's own minted bearer — this mode is an
        // eligibility check by design, so the app credential is correct here
        // (unlike the dead ownership mode, where it was the trap).
        wireMock.verify(getRequestedFor(urlEqualTo(VALIDATE))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withHeader("X-Api-Key", equalTo(API_KEY)));
    }

    @Test
    @DisplayName("the stored '+' is stripped to the platform's bare msisdn format")
    void check_stripsPlusForTheWire() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));

        client().checkCustomer(E164);

        wireMock.verify(getRequestedFor(urlEqualTo(VALIDATE)));
        wireMock.verify(0, getRequestedFor(urlMatching(".*\\+263.*")));
    }

    /** An UNSIGNED three-part JWT carrying just an {@code exp} — enough for the
     *  best-effort exp parse; the signature is never verified. */
    private static String jwtWithExp(long epochSeconds) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"exp\":" + epochSeconds + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    @Test
    @DisplayName("a JWT bearer is cached until its OWN exp, not the TTL fallback")
    void check_jwtExpIsHonouredForCaching() {
        // A JWT whose exp is comfortably ahead caches like any live token — one
        // login for two checks. Distinct from the opaque-token path below only
        // in which branch of deriveExpiry runs, but it pins that the exp parse
        // does not mis-read a valid future exp as already-expired.
        stubLogin(jwtWithExp(Instant.now().getEpochSecond() + 3600));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));
        InnbucksCustomerValidateClient c = client();

        c.checkCustomer(E164);
        c.checkCustomer(E164);

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a JWT whose exp is inside the 30s skew re-logs in every call — the exp IS parsed")
    void check_jwtExpInsideSkewForcesReLogin() {
        // exp only ~10s ahead: minus the 30s safety skew, the token is treated
        // as already expired, so a SECOND check re-logs in. An opaque token (TTL
        // fallback, 8 min) would cache — so seeing two logins proves the JWT exp
        // was read, not the fallback. A units bug (exp read as millis) would
        // cache for ~50 millennia and this test would drop to one login.
        stubLogin(jwtWithExp(Instant.now().getEpochSecond() + 10));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));
        InnbucksCustomerValidateClient c = client();

        c.checkCustomer(E164);
        c.checkCustomer(E164);

        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("the bearer is CACHED — two checks cost one login")
    void check_tokenIsCachedAcrossCalls() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));
        InnbucksCustomerValidateClient c = client();

        c.checkCustomer(E164);
        c.checkCustomer(E164);

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(2, getRequestedFor(urlEqualTo(VALIDATE)));
    }

    @Test
    @DisplayName("a 401 on the validate call forces ONE token refresh and replays")
    void check_401RefreshesTokenOnceAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("refresh")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"tok-stale\"}"))
                .willSetStateTo("second-login"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("refresh")
                .whenScenarioStateIs("second-login")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"tok-fresh\"}")));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .withHeader("Authorization", equalTo("Bearer tok-stale"))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .withHeader("Authorization", equalTo("Bearer tok-fresh"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Customer("00"));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a 403 on the validate call also forces one refresh and replays (same as 401)")
    void check_403RefreshesTokenOnceAndReplays() {
        // 403 is the other credential-refusal the client retries — an expired or
        // scope-narrowed bearer can present as either. Untested, this half of the
        // contract could silently break while the 401 path stayed green.
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("refresh403")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"tok-stale\"}"))
                .willSetStateTo("second-login"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("refresh403")
                .whenScenarioStateIs("second-login")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"tok-fresh\"}")));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .withHeader("Authorization", equalTo("Bearer tok-stale"))
                .willReturn(aResponse().withStatus(403)));
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .withHeader("Authorization", equalTo("Bearer tok-fresh"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"00\"}")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Customer("00"));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("SECURITY: our credentials refused twice is Unavailable — an outage, never 'not a customer'")
    void check_persistentCredentialRefusal_isUnavailable() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE)).willReturn(aResponse().withStatus(401)));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("credentials_rejected"));
        // Refreshes EXACTLY once before giving up — never a login-retry loop that
        // would hammer the auth endpoint on a sustained outage.
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(2, getRequestedFor(urlEqualTo(VALIDATE)));
    }

    @Test
    @DisplayName("SECURITY: a 200 carrying a FAILURE code is NotACustomer, never a pass")
    void check_2xxWithFailureCode_isNotACustomer() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":\"06\",\"responseMessage\":\"Account not found\"}")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.NotACustomer("code_06"));
    }

    @Test
    @DisplayName("404 on the msisdn maps to NotACustomer with a distinct reason (documented assumption)")
    void check_404_isNotACustomer() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE)).willReturn(aResponse().withStatus(404)));

        // Distinct reason ON PURPOSE: a wrong validate-path also 404s, and the
        // way that misconfiguration surfaces is every phone reading http_404.
        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.NotACustomer("http_404"));
    }

    @Test
    @DisplayName("5xx is Unavailable — the platform being down is not a verdict on the msisdn")
    void check_500_isUnavailable() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE)).willReturn(aResponse().withStatus(500)));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("http_500"));
    }

    @Test
    @DisplayName("a 2xx WAF block page (200 + text/html) is Unavailable, never NotACustomer")
    void check_htmlBlockPage_isUnavailable() {
        // The EcoCash edge lesson: bot mitigation serves its block page with
        // HTTP 200 and text/html. Calling that a refusal would read every
        // customer as a non-customer for as long as the block lasted.
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Request Rejected. Support ID: 1234</body></html>")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("malformed_2xx"));
    }

    @Test
    @DisplayName("a 2xx JSON object with NO responseCode is Unavailable, not a pass")
    void check_2xxWithoutResponseCode_isUnavailable() {
        stubLogin("tok-1");
        wireMock.stubFor(get(urlEqualTo(VALIDATE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"firstName\":\"Tawanda\"}")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("no_response_code"));
    }

    @Test
    @DisplayName("a refused login is Unavailable and the validate endpoint is never called")
    void check_loginRejected_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(401)));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("login_rejected"));
        wireMock.verify(0, getRequestedFor(urlEqualTo(VALIDATE)));
    }

    @Test
    @DisplayName("a login answering with no accessToken is Unavailable")
    void check_loginWithoutToken_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseMessage\":\"ok\"}")));

        assertThat(client().checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("login_no_token"));
    }

    @Test
    @DisplayName("connect-refused is Unavailable")
    void check_connectRefused_isUnavailable() {
        // Separate client at a closed port — never stop/restart the shared
        // WireMock, its dynamic port would change under the other tests.
        InnbucksCustomerValidateClient dead = newClient("http://localhost:1");

        assertThat(dead.checkCustomer(E164))
                .isInstanceOf(InnbucksCustomerValidateClient.Unavailable.class);
    }

    @Test
    @DisplayName("a blank phone never touches the network")
    void check_blankPhone_neverCallsOut() {
        assertThat(client().checkCustomer("  "))
                .isEqualTo(new InnbucksCustomerValidateClient.NotACustomer("blank_phone"));
        assertThat(client().checkCustomer(null))
                .isEqualTo(new InnbucksCustomerValidateClient.NotACustomer("blank_phone"));

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("an unconfigured client is Unavailable and never calls out")
    void check_unconfigured_isUnavailable() {
        InnbucksCustomerValidateClient unconfigured = new InnbucksCustomerValidateClient(
                "", "", "", "", LOGIN, "/auth/client-service/msisdn/{msisdn}/validate",
                "00", 480, 300, 300, new ObjectMapper());

        assertThat(unconfigured.checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("unconfigured"));
        assertThat(unconfigured.isConfigured()).isFalse();
        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    @DisplayName("configured = base URL AND api key AND username AND password AND both paths")
    void isConfigured_needsEverything() {
        ObjectMapper m = new ObjectMapper();
        String vp = "/auth/client-service/msisdn/{msisdn}/validate";
        assertThat(new InnbucksCustomerValidateClient("", API_KEY, "u", "p", LOGIN, vp, "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(new InnbucksCustomerValidateClient("http://x", "", "u", "p", LOGIN, vp, "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(new InnbucksCustomerValidateClient("http://x", API_KEY, "", "p", LOGIN, vp, "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(new InnbucksCustomerValidateClient("http://x", API_KEY, "u", "", LOGIN, vp, "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(new InnbucksCustomerValidateClient("http://x", API_KEY, "u", "p", "", vp, "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(new InnbucksCustomerValidateClient("http://x", API_KEY, "u", "p", LOGIN, "", "00", 480, 300, 300, m)
                .isConfigured()).isFalse();
        assertThat(client().isConfigured()).isTrue();
    }

    @Test
    @DisplayName("SECURITY: a validate-path with no {msisdn} placeholder fails CLOSED (unconfigured, never calls out)")
    void isConfigured_requiresTheMsisdnPlaceholder() {
        // A placeholder-less path makes replace("{msisdn}",...) a no-op, so every
        // phone would probe one literal URL — a mass fail-OPEN if that URL answers
        // a success code. Reading it as unconfigured turns that into a clean 503 /
        // skipped sweep. This is the config analogue of the /validate footgun.
        ObjectMapper m = new ObjectMapper();
        InnbucksCustomerValidateClient noPlaceholder = new InnbucksCustomerValidateClient(
                "http://localhost:" + wireMock.port(), API_KEY, "u", "p", LOGIN,
                "/auth/client-service/validate", "00", 480, 300, 300, m);

        assertThat(noPlaceholder.isConfigured()).isFalse();
        assertThat(noPlaceholder.checkCustomer(E164))
                .isEqualTo(new InnbucksCustomerValidateClient.Unavailable("unconfigured"));
        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }
}

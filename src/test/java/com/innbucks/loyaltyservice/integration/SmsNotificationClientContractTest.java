package com.innbucks.loyaltyservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.innbucks.loyaltyservice.config.InnbucksNotifyProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for loyalty's {@link SmsNotificationClient} against the InnBucks
 * public notification API — the SAME authenticated gateway email/payments use:
 * {@code POST /auth/third-party} for the bearer, then
 * {@code POST /api/notification/sms} with {@code X-Api-Key} + Bearer and body
 * {@code {message, reference, destinationMsisdn}}.
 *
 * <p>Previously loyalty SMS posted to the unauthenticated
 * {@code innbucks-core-gateway} {@code /notifications/sms} with
 * {@code {destination, senderId}}; this pins the new authed wire shape so a
 * regression to the old (broken) contract fails the build. Pure JUnit + WireMock.
 */
class SmsNotificationClientContractTest {

    private static final String LOGIN = "/auth/third-party";
    private static final String SMS = "/api/notification/sms";
    private static final String API_KEY = "test-api-key";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static SmsNotificationClient client(int port) {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl("http://localhost:" + port);
        props.setApiKey(API_KEY);
        props.setUsername("test-user");
        props.setPassword("test-pass");
        return new SmsNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + port).build(),
                props, new ObjectMapper());
    }

    @Test
    @DisplayName("happy path: logs in then posts {message,reference,destinationMsisdn} with X-Api-Key + Bearer")
    void sendSms_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendSms("+263782606983", "You earned 5 InnBucks points", "LOY-EARN-1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.message", equalTo("You earned 5 InnBucks points")))
                .withRequestBody(matchingJsonPath("$.destinationMsisdn", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("LOY-EARN-1"))));
    }

    @Test
    @DisplayName("blank recipient/message: guarded before any network call")
    void blankInputs_neverHitTheWire() {
        SmsNotificationClient client = client(wireMock.port());
        assertThatThrownBy(() -> client.sendSms(" ", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendSms("+263782606983", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("blank reference: auto-fills TKT-SMS-<uuid>")
    void blankReference_autoFilled() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendSms("+263782606983", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.reference", matching("TKT-SMS-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("401 on SMS: refresh token and replay once, then succeed")
    void unauthorized_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client(wireMock.port()).sendSms("+263782606983", "m", "r"))
                .doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(SMS)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("persistent 401: refresh once then give up with NotificationDeliveryException")
    void unauthorizedTwice_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendSms("+263782606983", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("login rejected (401): NotificationDeliveryException, SMS never attempted")
    void loginRejected_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendSms("+263782606983", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed");
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("500 on SMS (non-401): NotificationDeliveryException")
    void serverError_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client(wireMock.port()).sendSms("+263782606983", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void connectRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> client(closedPort).sendSms("+263782606983", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }
}

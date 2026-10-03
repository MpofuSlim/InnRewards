package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform login is single-flight: concurrent checks share one login, and a
 * login that does not answer is an OUTAGE for every caller waiting on it —
 * {@code Unavailable}, never {@code NotACustomer}.
 */
class InnbucksCustomerValidateClientConcurrencyTest {

    private static final String E164 = "+263782606983";
    private static final String LOGIN = "/auth/third-party";
    private static final String VALIDATE = "/auth/client-service/msisdn/263782606983/validate";
    private static final int CALLERS = 8;

    private static WireMockServer wireMock;
    private final ExecutorService pool = Executors.newFixedThreadPool(CALLERS);

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort().containerThreads(32));
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
        pool.shutdownNow();
    }

    private InnbucksCustomerValidateClient client(int readTimeoutMs) {
        return new InnbucksCustomerValidateClient(
                "http://localhost:" + wireMock.port(), "key", "svc-user", "svc-pass",
                LOGIN, "/auth/client-service/msisdn/{msisdn}/validate",
                "00,000,0", 480, 500, readTimeoutMs, new ObjectMapper());
    }

    private List<InnbucksCustomerValidateClient.CustomerCheckOutcome> runConcurrently(
            InnbucksCustomerValidateClient c) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InnbucksCustomerValidateClient.CustomerCheckOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return c.checkCustomer(E164);
            }));
        }
        go.countDown();
        List<InnbucksCustomerValidateClient.CustomerCheckOutcome> out = new ArrayList<>();
        for (Future<InnbucksCustomerValidateClient.CustomerCheckOutcome> f : futures) {
            out.add(f.get(20, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("concurrent cold checks share ONE slow login")
    void concurrentColdChecks_shareOneLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(700)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"accessToken\":\"tok-1\"}")));
        wireMock.stubFor(get(urlEqualTo(VALIDATE)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responseCode\":\"00\"}")));

        List<InnbucksCustomerValidateClient.CustomerCheckOutcome> outcomes = runConcurrently(client(3000));

        assertThat(outcomes).allMatch(o -> o.equals(new InnbucksCustomerValidateClient.Customer("00")));
        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("SECURITY: a login that never answers is Unavailable for every waiting caller, never 'not a customer'")
    void hungLogin_isUnavailable_forEveryCaller() throws Exception {
        // The login outlives the read timeout: the caller running it fails with
        // login_io_error, and every caller joined to it gets the same outage.
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(3000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"accessToken\":\"tok-1\"}")));

        List<InnbucksCustomerValidateClient.CustomerCheckOutcome> outcomes = runConcurrently(client(400));

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o).isInstanceOf(InnbucksCustomerValidateClient.Unavailable.class);
            assertThat(((InnbucksCustomerValidateClient.Unavailable) o).reason())
                    .isIn("login_io_error", "login_timeout");
        });
        // Far fewer logins than callers: the joiners did not each start one.
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(LOGIN)))).hasSizeLessThan(CALLERS);
    }
}

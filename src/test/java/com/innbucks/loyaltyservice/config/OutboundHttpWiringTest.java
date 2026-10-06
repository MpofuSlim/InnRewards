package com.innbucks.loyaltyservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.client.InnbucksSessionClient;
import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.client.VeenguIdentityClient;
import com.innbucks.loyaltyservice.testsupport.TestOutboundHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound {@code RestClient} in the service draws on the ONE pool and
 * keeps its own timeouts. A client built on Spring's default factory (or a
 * fresh {@code SimpleClientHttpRequestFactory}) fails here — that is the
 * regression this pins: an unpooled client opens a TCP (and TLS) connection
 * per call, and a default factory can silently change transport.
 */
class OutboundHttpWiringTest {

    private static final OutboundHttp POOL = TestOutboundHttp.POOL;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("InnbucksCustomerValidateClient: pooled, with its configured connect/read timeouts")
    void validateClient() {
        var c = new InnbucksCustomerValidateClient(POOL, "http://x", "k", "u", "p", "/login",
                "/v/{msisdn}", "00", 480, 3100, 6100, JSON);
        assertPooled(field(c), 3100, 6100);
    }

    @Test
    @DisplayName("InnbucksSessionClient: pooled, with its configured connect/read timeouts")
    void sessionClient() {
        var c = new InnbucksSessionClient(POOL, "http://x", "k", "/p/{msisdn}", "00", 3200, 6200, JSON);
        assertPooled(field(c), 3200, 6200);
    }

    @Test
    @DisplayName("VeenguIdentityClient: pooled, with its configured connect/read timeouts")
    void veenguClient() {
        var c = new VeenguIdentityClient(POOL, "http://x", "t", 3300, 6300, JSON);
        assertPooled(field(c), 3300, 6300);
    }

    @Test
    @DisplayName("UserServiceClient: pooled over the load-balanced builder, with its own timeouts")
    void userServiceClient() {
        var c = new UserServiceClient(POOL, RestClient.builder(), "http://user-service", 2100, 5100, "t", JSON);
        assertPooled(field(c), 2100, 5100);
    }

    @Test
    @DisplayName("notification API + WhatsApp RestClient beans: pooled, with their properties' timeouts")
    void notificationClients() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        notify.setBaseUrl("http://notify");
        notify.setConnectTimeoutMs(3400);
        notify.setReadTimeoutMs(20400);
        assertPooled(new NotificationClientConfig().innbucksNotifyRestClient(notify, POOL), 3400, 20400);

        WhatsAppProperties wa = new WhatsAppProperties();
        wa.setBaseUrl("http://wa");
        wa.setConnectTimeoutMs(2500);
        wa.setReadTimeoutMs(10500);
        assertPooled(new NotificationClientConfig().whatsAppRestClient(wa, POOL), 2500, 10500);
    }

    @Test
    @DisplayName("both RestClient.Builder beans start on the pool with the shared default timeouts")
    void builders() {
        LoadBalancedRestClientConfig cfg = new LoadBalancedRestClientConfig();
        assertPooled(cfg.restClientBuilder(POOL).build(), 2000, 10000);
        assertPooled(cfg.loadBalancedRestClientBuilder(POOL).build(), 2000, 10000);
    }

    @Test
    @DisplayName("the property defaults: shipped timeouts the clients above keep are unchanged")
    void shippedDefaultsAreKept() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        assertThat(notify.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(notify.getReadTimeoutMs()).isEqualTo(20000);
        WhatsAppProperties wa = new WhatsAppProperties();
        assertThat(wa.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(wa.getReadTimeoutMs()).isEqualTo(10000);
    }

    private static RestClient field(Object client) {
        return (RestClient) ReflectionTestUtils.getField(client, "restClient");
    }

    private static void assertPooled(RestClient rc, long connectMs, long readMs) {
        ClientHttpRequestFactory f = (ClientHttpRequestFactory) ReflectionTestUtils.getField(rc, "clientRequestFactory");
        assertThat(f).isInstanceOf(OutboundHttp.PooledRequestFactory.class);
        OutboundHttp.PooledRequestFactory pooled = (OutboundHttp.PooledRequestFactory) f;
        assertThat(pooled.getHttpClient()).as("the shared pool's client").isSameAs(POOL.httpClient());
        assertThat(pooled.connectTimeout()).isEqualTo(Duration.ofMillis(connectMs));
        assertThat(pooled.responseTimeout()).isEqualTo(Duration.ofMillis(readMs));
        var rcfg = pooled.effectiveRequestConfig();
        assertThat(OutboundHttpTest.connectTimeoutOf(rcfg)).isEqualTo(connectMs);
        assertThat(rcfg.getResponseTimeout().toMilliseconds()).isEqualTo(readMs);
        assertThat(rcfg.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(Math.min(2000, connectMs));
    }
}

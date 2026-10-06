package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient beans for the two outbound notification gateways: the InnBucks
 * public notification API (SMS, authed — X-Api-Key + bearer from
 * /auth/third-party, handled in {@code SmsNotificationClient}) and the WhatsApp
 * gateway. Both are external services reached by an
 * explicit {@code base-url} (not the discovery map), with the same correlation-ID
 * propagation booking-service uses so a checkout's traceId follows the
 * notification across the wire.
 *
 * <p>Both draw their connections from the service's one pool
 * ({@link OutboundHttp}) and keep their own connect/read timeouts. The contract
 * tests build them through these same methods.
 */
@Configuration
@EnableConfigurationProperties({WhatsAppProperties.class, InnbucksNotifyProperties.class})
public class NotificationClientConfig {

    @Bean("innbucksNotifyRestClient")
    public RestClient innbucksNotifyRestClient(InnbucksNotifyProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(outboundHttp.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }

    @Bean("whatsAppRestClient")
    public RestClient whatsAppRestClient(WhatsAppProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(outboundHttp.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }
}

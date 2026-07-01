package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient beans for the two outbound notification gateways used by the
 * guest-checkout congratulations flow: the InnBucks core gateway (SMS, primary)
 * and the WhatsApp gateway (fallback). Both are external services reached by an
 * explicit {@code base-url} (not Eureka), with the same correlation-ID
 * propagation booking-service uses so a checkout's traceId follows the
 * notification across the wire.
 */
@Configuration
@EnableConfigurationProperties({WhatsAppProperties.class, InnbucksGatewayProperties.class})
public class NotificationClientConfig {

    @Bean("innbucksGatewayRestClient")
    public RestClient innbucksGatewayRestClient(InnbucksGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }

    @Bean("whatsAppRestClient")
    public RestClient whatsAppRestClient(WhatsAppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }
}

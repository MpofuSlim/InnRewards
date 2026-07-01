package com.innbucks.loyaltyservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the InnBucks core gateway adapter (used here for SMS delivery as
 * the PRIMARY channel for the guest-checkout congratulations message). Host-
 * resident service (not in the ticketing Eureka), so reached via an explicit
 * {@code base-url}, never {@code lb://}. Same env-var convention
 * ({@code INNBUCKS_GATEWAY_URL}) as the existing booking-service binding.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-gateway")
public class InnbucksGatewayProperties {
    /** Gateway base URL, e.g. http://10.0.155.69:8088 (no trailing path). */
    private String baseUrl;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}

package com.innbucks.loyaltyservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway used to deliver the
 * guest-checkout congratulations message (SMS primary, WhatsApp fallback).
 * Third-party service (not in Eureka), so consumed via a plain RestClient with
 * an explicit {@code base-url}. Same env-var convention ({@code WHATSAPP_*}) as
 * booking-service / payment-service so every service reads the same values from
 * the deployment env.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}

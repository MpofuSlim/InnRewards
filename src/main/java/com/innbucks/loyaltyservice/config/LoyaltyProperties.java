package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loyalty")
public record LoyaltyProperties(
        Voucher voucher,
        Qr qr,
        Integration integration,
        Invoice invoice
) {
    public LoyaltyProperties {
        if (voucher == null) voucher = new Voucher("change-me-voucher-secret-change-me-voucher-secret", 365, 5, 60);
        if (qr == null) qr = new Qr("change-me-qr-secret-change-me-qr-secret-change-me", 300);
        if (integration == null) integration = new Integration(false);
        if (invoice == null) invoice = new Invoice("INV");
    }

    public record Voucher(String secret, int defaultValidityDays, int fraudVelocityThreshold, int fraudWindowSeconds) {}
    public record Qr(String secret, int ttlSeconds) {}
    public record Integration(boolean mpesaEnabled) {}
    public record Invoice(String prefix) {}
}

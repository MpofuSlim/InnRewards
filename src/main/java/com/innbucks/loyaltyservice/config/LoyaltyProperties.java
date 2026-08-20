package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loyalty")
public record LoyaltyProperties(
        Voucher voucher,
        Qr qr,
        Integration integration,
        Invoice invoice,
        Earn earn
) {
    public LoyaltyProperties {
        if (voucher == null) voucher = new Voucher("change-me-voucher-secret-change-me-voucher-secret", 365, 5, 60);
        if (qr == null) qr = new Qr("change-me-qr-secret-change-me-qr-secret-change-me", 300);
        if (integration == null) integration = new Integration(false);
        if (invoice == null) invoice = new Invoice("INV");
        if (earn == null) earn = new Earn(true, true);
    }

    public record Voucher(String secret, int defaultValidityDays, int fraudVelocityThreshold, int fraudWindowSeconds) {}
    public record Qr(String secret, int ttlSeconds) {}
    public record Integration(boolean mpesaEnabled) {}
    public record Invoice(String prefix) {}

    /**
     * Earn-integrity guards on the TYPED_PHONE channel (the one where staff
     * key the recipient). Both default ON — fail closed; the flags exist as
     * an operational escape hatch, not as the expected state.
     *
     * @param selfBlock        refuse an earn whose recipient phone matches the
     *                         authenticated caller's own phone (SELF_EARN).
     * @param requireReference refuse a staff-typed PURCHASE / CARD_PAYMENT
     *                         earn that names no receipt reference
     *                         (REFERENCE_REQUIRED) — without it the earn can
     *                         never be reconciled against the till's sales.
     */
    public record Earn(boolean selfBlock, boolean requireReference) {}
}

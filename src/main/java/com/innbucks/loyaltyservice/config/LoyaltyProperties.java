package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loyalty")
public record LoyaltyProperties(
        Voucher voucher,
        Qr qr,
        Integration integration,
        Invoice invoice,
        Earn earn,
        Adjustment adjustment
) {
    public LoyaltyProperties {
        if (voucher == null) voucher = new Voucher("change-me-voucher-secret-change-me-voucher-secret", 365, 5, 60);
        if (qr == null) qr = new Qr("change-me-qr-secret-change-me-qr-secret-change-me", 300);
        if (integration == null) integration = new Integration(false);
        if (invoice == null) invoice = new Invoice("INV");
        if (earn == null) earn = new Earn(true, true, true, 300);
        if (adjustment == null) adjustment = new Adjustment(
                new java.math.BigDecimal("5000"), new java.math.BigDecimal("20000"));
    }

    public record Voucher(String secret, int defaultValidityDays, int fraudVelocityThreshold, int fraudWindowSeconds) {}

    /**
     * Ceilings on manual point adjustments — the ONE path that mints points
     * from nothing. Earning is bounded by a real transaction amount and a
     * rule's earn rate; redemption is bounded by the balance. An adjustment
     * has no natural bound at all, so without these a single SHOP_ADMIN could
     * credit an arbitrary figure to any account in their tenant — including one
     * they control — and redeem it immediately. Every existing control on this
     * path (postedBy attribution, the customer notification, the reports) is
     * after the fact; these are the only ones that stop it happening.
     *
     * <p>SUPER_ADMIN is exempt from both. The point is not to make large
     * corrections impossible, it is to make them require someone who is
     * accountable for them — a shop manager can still fix a small counter
     * mistake without raising a ticket.
     *
     * <p>Both are compared on the ABSOLUTE value: a large debit is as much a
     * red flag as a large credit (wiping a balance is the shape a disgruntled
     * operator uses), and it is the movement's size that matters, not its sign.
     *
     * @param maxPerAdjustment ceiling on one adjustment. Non-positive disables
     *                         the check.
     * @param maxDailyPerOperator ceiling on the summed absolute adjustments one
     *                         operator may post across a rolling 24h. Catches
     *                         the obvious evasion — slicing one large
     *                         adjustment into many small ones. Non-positive
     *                         disables the check.
     */
    public record Adjustment(java.math.BigDecimal maxPerAdjustment,
                             java.math.BigDecimal maxDailyPerOperator) {}
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
     * @param staffRecipientBlock refuse a staff-typed earn whose recipient
     *                         phone belongs to ANY staff member of the same
     *                         merchant (STAFF_RECIPIENT) — the
     *                         colleague-crediting shape SELF_EARN cannot see.
     *                         Fails open when user-service is unreachable.
     * @param staffCacheSeconds TTL of the per-merchant staff-phone cache. Also
     *                         bounds how long the guard stays degraded after a
     *                         failed lookup, and how long a staff-list change
     *                         takes to propagate.
     */
    public record Earn(boolean selfBlock, boolean requireReference,
                       boolean staffRecipientBlock, int staffCacheSeconds) {}
}

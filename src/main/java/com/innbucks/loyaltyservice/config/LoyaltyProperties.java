package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loyalty")
public record LoyaltyProperties(
        Voucher voucher,
        Qr qr,
        Integration integration,
        Invoice invoice,
        Earn earn,
        Adjustment adjustment,
        EarnRate earnRate
) {
    public LoyaltyProperties {
        if (voucher == null) voucher = new Voucher("change-me-voucher-secret-change-me-voucher-secret", 365, 5, 60);
        if (qr == null) qr = new Qr("change-me-qr-secret-change-me-qr-secret-change-me", 300);
        if (integration == null) integration = new Integration(false);
        if (invoice == null) invoice = new Invoice("INV");
        if (earn == null) earn = new Earn(true, true, true, 300);
        if (adjustment == null) adjustment = new Adjustment(
                new java.math.BigDecimal("5000"), new java.math.BigDecimal("20000"));
        if (earnRate == null) earnRate = new EarnRate(
                new java.math.BigDecimal("1000"), new java.math.BigDecimal("100"));
    }

    /**
     * Platform ceilings on how RICH an earn rule may be — the backstop on the
     * points the platform can be made liable for by a merchant-set rate.
     *
     * <p>A merchant sets its own {@code pointsPerUnit} and campaign multipliers,
     * but InnBucks carries the liability for every point issued, so an
     * unbounded rate is an unbounded liability: a merchant (or a compromised
     * merchant-admin token) could set {@code pointsPerUnit = 1_000_000} and mint
     * an arbitrary balance on a $1 sale. These caps are enforced at rule/campaign
     * WRITE time — the earliest point — so a rule that would breach them is
     * refused rather than silently minting later.
     *
     * <p>Deliberately generous (defaults 1000 / 100x): they exist to stop the
     * absurd, not to second-guess a merchant's commercial rate. A legitimate
     * "1 point per $1" rate or a "2x weekend" promo sits far below them. Raise
     * per cell via {@code LOYALTY_EARN_RATE_MAX_POINTS_PER_UNIT} /
     * {@code LOYALTY_EARN_RATE_MAX_MULTIPLIER} if a real arrangement needs it.
     *
     * <p>Non-positive disables a ceiling (same convention as {@link Adjustment}),
     * for a cell that deliberately wants no cap.
     *
     * @param maxPointsPerUnit ceiling on a rule's {@code pointsPerUnit}.
     * @param maxMultiplier    ceiling on a rule's {@code multiplier} AND a
     *                         campaign's multiplier (both stack into the same
     *                         liability, so they share the cap).
     */
    public record EarnRate(java.math.BigDecimal maxPointsPerUnit,
                           java.math.BigDecimal maxMultiplier) {}

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

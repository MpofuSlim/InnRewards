package com.innbucks.loyaltyservice.entity;

/**
 * How an earn transaction arrived — the earn-integrity design's channel
 * dimension. Stored on {@link LoyaltyTransaction#getChannel()} for EARN rows
 * only (reversals / adjustments / transfers / redemptions carry {@code null}).
 *
 * <p>The distinction is load-bearing, not descriptive: the anti-diversion
 * guards ({@code SELF_EARN}, {@code REFERENCE_REQUIRED}) fire ONLY on
 * {@link #TYPED_PHONE}, because that is the one channel where a staff member
 * chooses the recipient. {@link #QR_PRESENCE} is exempt by design — the
 * consume flow credits only the authenticated scanner, so the caller being
 * the recipient is the point, not the fraud.
 */
public enum EarnChannel {

    /** Staff keyed the recipient (userId or phone) at the till — the
     *  discretionary channel every earn-integrity guard targets. */
    TYPED_PHONE,

    /** The customer scanned a merchant QR; {@code QrService.consume} credits
     *  only the authenticated scanner. Presence-proof by construction. */
    QR_PRESENCE,

    /** Server-side flow with no staff discretion over the recipient:
     *  guest / shop checkout and the ticketing accrual integration. */
    CHECKOUT_S2S,

    /** Reserved for phase 3 (receipt-claim earns: the POS posts the sale with
     *  no recipient; whoever holds the printed claim code claims it). Not yet
     *  emitted. */
    RECEIPT_CLAIM
}

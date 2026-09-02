package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A redemption lost the {@code (merchant, reference)} unique-index race: two
 * near-simultaneous redeems with the same idempotency key, where the OTHER
 * request won the insert (and performed the single debit). This loser never
 * reached the wallet — no double debit — but the order IS redeemed.
 *
 * <p>It IS a {@link LoyaltyException} with the SAME 409 / {@code DUPLICATE_REFERENCE}
 * mapping the race threw before, so any caller that simply lets it propagate —
 * the in-process callers that join the redeem transaction and can't retry —
 * still surfaces the identical 409. Behaviour there is unchanged.
 *
 * <p>Its distinct TYPE is what lets the idempotent retry wrapper
 * ({@code RedemptionService.redeemPointsIdempotent}) tell this race apart from
 * the <em>cross-type</em> pre-check conflict (a plain {@link LoyaltyException}
 * with the same code, thrown BEFORE any insert when the reference already
 * belongs to a non-redemption transaction). Only the race is safe to retry: on
 * retry the pre-check finds the winner's committed row and returns the clean
 * 200 replay. The cross-type conflict is a genuine error and must NOT be retried.
 */
public class RedemptionRaceException extends LoyaltyException {

    public RedemptionRaceException() {
        super(HttpStatus.CONFLICT, "DUPLICATE_REFERENCE",
                "A redemption with this reference is already being processed.");
    }
}

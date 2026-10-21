package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A voucher redemption lost the {@code (merchant, reference)} unique-index race
 * (V61, {@code uq_voucher_redemption_merchant_reference}): another use with the
 * same idempotency key committed first. This attempt's transaction rolled back
 * whole — no balance was taken, no row written, no message sent.
 *
 * <p>Two uses of the SAME voucher never get here: both take the voucher's row
 * lock first, so the second waits, then finds the first's committed row in the
 * reference pre-check and replays it. Only the same reference arriving on two
 * DIFFERENT vouchers at once can race the index — and that is the
 * {@code DUPLICATE_REFERENCE} conflict either way.
 *
 * <p>Same pattern as {@link RedemptionRaceException}: it IS a
 * {@link LoyaltyException} with the pre-check's 409 code and message, so a caller
 * that lets it propagate answers exactly what the pre-check would have, and its
 * distinct TYPE lets {@code VoucherService.redeemIdempotent} retry it once into
 * that pre-check, from a fresh transaction (a unique violation aborts the
 * Postgres transaction it happened in, so no re-read inside it could work).
 */
public class VoucherRedemptionRaceException extends LoyaltyException {

    public VoucherRedemptionRaceException() {
        super(HttpStatus.CONFLICT, "DUPLICATE_REFERENCE",
                "This reference was already used for another voucher redemption.");
    }
}

package com.innbucks.loyaltyservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A voucher refusal that counts as a GUESS toward the redeem lockout
 * ({@code VoucherGuessGuard}): the caller presented a code that is not theirs to
 * use and learned nothing they were entitled to.
 *
 * <p>Two shapes, and only these:
 * <ul>
 *   <li>{@link #unknownCode()} — no such voucher in the caller's tenant. The
 *   body is byte-identical to {@link LoyaltyException#notFound(String)
 *   notFound("voucher")}, so the wire contract is unchanged.</li>
 *   <li>A live code that belongs to somebody else, refused to a customer bearer
 *   ({@code NOT_VOUCHER_OWNER}) — see {@link #from(LoyaltyException)}.</li>
 * </ul>
 *
 * <p>Deliberately NOT a guess, and therefore thrown as a plain
 * {@link LoyaltyException}: a code with a detectable typo
 * ({@code VOUCHER_CODE_MISTYPED}, computed from the code alone, so it reveals
 * nothing), EXPIRED, REVOKED, ALREADY_REDEEMED, WRONG_MERCHANT and the holder
 * account states. Those are honest presentations of a real code — a double tap
 * at a till, a customer at the wrong shop — and counting them would lock out
 * people who are not guessing.
 *
 * <p>Same pattern as {@link RedemptionRaceException}: it IS a
 * {@link LoyaltyException}, so every existing handler and caller treats it
 * exactly as before; only the redeem controllers look at the type.
 */
public class VoucherCodeGuessException extends LoyaltyException {

    private VoucherCodeGuessException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /** No voucher with this code in the caller's tenant. Wire-identical to notFound("voucher"). */
    public static VoucherCodeGuessException unknownCode() {
        LoyaltyException nf = LoyaltyException.notFound("voucher");
        return new VoucherCodeGuessException(nf.getStatus(), nf.getCode(), nf.getMessage());
    }

    /**
     * Re-types an existing refusal as a counted guess, keeping its status, code
     * and message exactly, so the response a client sees does not change.
     */
    public static VoucherCodeGuessException from(LoyaltyException refusal) {
        return new VoucherCodeGuessException(refusal.getStatus(), refusal.getCode(), refusal.getMessage());
    }
}

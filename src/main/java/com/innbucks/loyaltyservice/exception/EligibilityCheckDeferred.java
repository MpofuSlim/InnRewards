package com.innbucks.loyaltyservice.exception;

import com.innbucks.loyaltyservice.util.MsisdnMasking;

/**
 * Control flow, not an error: the spend gate found an unregistered phone that
 * the on-demand eligibility check (V44) could answer for, and is inside a
 * transaction where asking would hold a pooled connection — and on the voucher
 * path the voucher's {@code PESSIMISTIC_WRITE} row lock — for the length of an
 * HTTP call to the InnBucks directory.
 *
 * <p>Throwing it rolls the spend back (nothing has been committed when the gate
 * runs), which releases the lock and the connection. {@code EligibilityDeferral}
 * catches it with no transaction open, asks the directory, registers a
 * confirmed customer in a transaction of its own, and replays the spend once.
 *
 * <p>Deliberately NOT a {@link LoyaltyException} and NOT a
 * {@link VoucherCodeGuessException}: nothing that maps or counts refusals —
 * {@code GlobalExceptionHandler}'s typed handlers, the S2S controllers'
 * {@code catch (LoyaltyException)}, {@code ShopCheckoutService}'s rejected-mode
 * metric, the redeem lockout — may mistake it for a refusal. It is only ever
 * thrown while a deferral scope is active, and that scope always catches it, so
 * it never reaches a client.
 *
 * <p>No stack trace (it is thrown on an ordinary path) and the phone is masked
 * in the message — it is a customer's number.
 */
public class EligibilityCheckDeferred extends RuntimeException {

    private final transient String phone;

    public EligibilityCheckDeferred(String e164Phone) {
        super("On-demand eligibility check deferred for phone=" + MsisdnMasking.mask(e164Phone),
                null, false, false);
        this.phone = e164Phone;
    }

    /** The E.164 phone of the account the spend was gated on — read off that account. */
    public String phone() {
        return phone;
    }
}

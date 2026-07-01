package com.innbucks.loyaltyservice.util;

/**
 * Last-4-digits MSISDN mask for log lines. Full phone numbers in
 * structured logs are a Kenya Data Protection Act / banking-PII finding
 * — the customer's MSISDN is account-binding identity. Audit recommended
 * masking to the last four digits ({@code ****4008}). Operators still
 * get enough to disambiguate sessions; logs are no longer a join-key
 * back to the customer's full identity if they leak.
 *
 * <p>Sibling of {@code innbucks.paymentservice.util.MsisdnMasking} —
 * intentionally duplicated per service because the project has no shared
 * utility module. Keep the behaviour identical across copies.
 */
public final class MsisdnMasking {

    private MsisdnMasking() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}

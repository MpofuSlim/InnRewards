package com.innbucks.loyaltyservice.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spellings one phone number may have been STORED under, for matching the
 * columns that were written as someone typed them rather than canonicalised.
 *
 * <p>{@code loyalty_users}, {@code wallets}, {@code phone_registrations} and the
 * refresh tokens all hold canonical E.164, so a support lookup matches them
 * exactly. The voucher sender phone ({@code vouchers.sender_phone}, "never
 * validated"), an assignee phone given alongside an account id, a transferred-
 * from phone copied off either, and the three phone columns on
 * {@code voucher_purchase_orders} are stored as the staff member typed them. A
 * customer who gifted a voucher as {@code 0771234567} would otherwise be
 * invisible to a lookup of {@code +263771234567}.
 *
 * <p>Covers the four spellings a till actually produces: {@code +263771234567},
 * {@code 263771234567}, {@code 0771234567} (national trunk prefix) and
 * {@code 771234567}. A number typed with spaces or dashes is NOT matched — that
 * would need a normalised column, not more spellings.
 */
public final class PhoneSpellings {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private PhoneSpellings() {}

    /**
     * @param e164 an already-canonical E.164 phone ({@code UserService.normalizePhone}'s output)
     * @return the E.164 itself first, then the other spellings; never empty
     */
    public static List<String> of(String e164) {
        Set<String> out = new LinkedHashSet<>();
        out.add(e164);
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(e164, null);
            String nsn = PHONE_UTIL.getNationalSignificantNumber(parsed);
            out.add(parsed.getCountryCode() + nsn);
            out.add("0" + nsn);
            out.add(nsn);
        } catch (NumberParseException e) {
            // Not E.164 after all: match it exactly and nothing else.
        }
        return List.copyOf(out);
    }
}

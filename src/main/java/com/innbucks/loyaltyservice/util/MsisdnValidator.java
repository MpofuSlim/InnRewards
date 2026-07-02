package com.innbucks.loyaltyservice.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;

/**
 * Validates and canonicalises phone numbers to E.164 ({@code +<cc><national>})
 * before they're stored on a {@code loyalty_users} / {@code wallets} row or used
 * as a phone-keyed lookup.
 *
 * <p>Phone is the identity key on the loyalty side — {@code uk_user_tenant_phone}
 * scopes a customer per tenant, wallets are keyed by phone, and cross-service
 * calls (user-service's promote webhook, the loyalty projection of a booking)
 * arrive as phone. If loyalty stored a different spelling of a number than
 * user-service did, the projection would fork: points credited to
 * {@code 0772...} while the registered customer is {@code +263772...} can never
 * be spent. Canonicalising every write AND every phone-keyed read here keeps the
 * loyalty projection aligned with the customer record in user-service.
 *
 * <p>Deliberately identical in shape + library to user-service's and
 * booking-service's {@code MsisdnValidator} (this repo duplicates small utils
 * per module — see {@code MsisdnMasking}). Byte-for-byte compatibility means a
 * number normalised in any service is the same string in every other, so
 * cross-service joins on phone hold.
 *
 * <p>Normalisation is idempotent — {@code normalizeToE164("+263782606983", "ZW")}
 * returns {@code "+263782606983"} — so it's safe to apply defensively at both
 * the {@code UserService} and {@code WalletService} entry points without double
 * transformation.
 */
public final class MsisdnValidator {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private MsisdnValidator() {
    }

    /**
     * Validate {@code raw} and return it in canonical E.164 form
     * ({@code +<cc><national>}), or {@link Optional#empty()} if it isn't a valid
     * phone number.
     *
     * @param raw           the caller-supplied number; may or may not carry a
     *                      leading {@code +}, may carry a national trunk
     *                      {@code 0} ({@code 0782...}).
     * @param defaultRegion ISO 3166-1 alpha-2 region used to interpret a number
     *                      that lacks a {@code +} country code (the deployment's
     *                      cell country, e.g. {@code "ZW"}). Numbers that DO
     *                      carry a {@code +} are parsed by their own country code
     *                      and the default region is ignored.
     */
    public static Optional<String> normalizeToE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(raw.trim(), defaultRegion);
            if (!PHONE_UTIL.isValidNumber(parsed)) {
                return Optional.empty();
            }
            return Optional.of(PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /** True when {@code raw} is a valid phone number under {@code defaultRegion}. */
    public static boolean isValid(String raw, String defaultRegion) {
        return normalizeToE164(raw, defaultRegion).isPresent();
    }
}

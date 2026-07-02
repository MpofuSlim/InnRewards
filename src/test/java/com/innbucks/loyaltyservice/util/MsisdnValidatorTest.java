package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins loyalty-service's phone canonicalisation. Loyalty keys its projection off
 * phone ({@code uk_user_tenant_phone}, wallets, the user-service promote
 * webhook), so it must produce the exact same E.164 string user-service stores —
 * otherwise points credited to one spelling of a number can never be spent by
 * the customer registered under another. These tests lock that alignment.
 */
class MsisdnValidatorTest {

    @Test
    void bareSubscriberNumber_gainsCountryCodeFromDefaultRegion() {
        assertThat(MsisdnValidator.normalizeToE164("782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void localTrunkZeroForm_isCanonicalised() {
        assertThat(MsisdnValidator.normalizeToE164("0782606983", "ZW"))
                .contains("+263782606983");
    }

    @Test
    void alreadyE164_isReturnedUnchanged_idempotent() {
        String once = MsisdnValidator.normalizeToE164("+263771234567", "ZW").orElseThrow();
        assertThat(once).isEqualTo("+263771234567");
        assertThat(MsisdnValidator.normalizeToE164(once, "ZW")).contains("+263771234567");
    }

    @Test
    void kenyaCellDefaultRegion_appliesKenyaCountryCode() {
        assertThat(MsisdnValidator.normalizeToE164("712345678", "KE"))
                .contains("+254712345678");
    }

    @Test
    void foreignNumberWithPlus_keepsItsOwnCountryCode_notDefaultRegion() {
        assertThat(MsisdnValidator.normalizeToE164("+254712345678", "ZW"))
                .contains("+254712345678");
    }

    @Test
    void invalidOrBlank_isRejected() {
        assertThat(MsisdnValidator.normalizeToE164("+26378260983", "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164(null, "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164("   ", "ZW")).isEmpty();
        assertThat(MsisdnValidator.normalizeToE164("not-a-phone", "ZW")).isEmpty();
    }
}

package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spellings a support lookup matches against columns written as typed
 * (voucher sender, voucher-order phones). The E.164 must come first and every
 * spelling a till produces must be present, or a customer who gifted a voucher
 * as "0771234567" is invisible to a lookup of "+263771234567".
 */
class PhoneSpellingsTest {

    @Test
    @DisplayName("a ZW number yields E.164, bare international, national-with-trunk and bare national")
    void zimbabwe() {
        assertThat(PhoneSpellings.of("+263771234567"))
                .containsExactly("+263771234567", "263771234567", "0771234567", "771234567");
    }

    @Test
    @DisplayName("another market uses its own country code — nothing is hard-coded to +263")
    void kenya() {
        assertThat(PhoneSpellings.of("+254712345678"))
                .containsExactly("+254712345678", "254712345678", "0712345678", "712345678");
    }

    @Test
    @DisplayName("something that is not E.164 is matched exactly and nothing else")
    void notE164() {
        assertThat(PhoneSpellings.of("not-a-phone")).containsExactly("not-a-phone");
    }
}

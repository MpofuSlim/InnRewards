package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the voucher code format: sixteen digits, first never {@code 0}. The leading
 * digit rule is the one a spreadsheet would otherwise break — an exported
 * {@code 0123456789} reopens as {@code 123456789}, a code that never redeems.
 */
class CryptoSignerVoucherCodeTest {

    private static final int SAMPLES = 20_000;

    @Test
    void everyCodeIsSixteenDigits_neverStartingWithZero() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(CryptoSigner.randomNumericVoucherCode()).matches("[1-9][0-9]{15}");
        }
    }

    @Test
    void everyDigitIsReachable_inEveryPosition() {
        // A generator stuck on a narrowed range (an off-by-one in the first
        // digit, a modulo bias that never yields 9) shrinks the code space
        // without failing the format check above.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            String code = CryptoSigner.randomNumericVoucherCode();
            for (int pos = 0; pos < code.length(); pos++) {
                seen.add(pos + ":" + code.charAt(pos));
            }
        }
        for (char d = '1'; d <= '9'; d++) {
            assertThat(seen).contains("0:" + d);
        }
        assertThat(CryptoSigner.CODE_DIGITS).isEqualTo(16);
        for (int pos = 1; pos < CryptoSigner.CODE_DIGITS; pos++) {
            for (char d = '0'; d <= '9'; d++) {
                assertThat(seen).contains(pos + ":" + d);
            }
        }
    }

    @Test
    void codesDoNotRepeatInPractice() {
        // 9×10¹⁵ codes: 20k draws expect effectively no collisions, so any
        // repeat beyond a handful means the generator is not drawing from the full space.
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            codes.add(CryptoSigner.randomNumericVoucherCode());
        }
        assertThat(codes).hasSizeGreaterThan(SAMPLES - 5);
    }
}

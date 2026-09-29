package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.util.VoucherCodes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the issued voucher code: sixteen digits, first never {@code 0}, last a
 * check digit that makes it well-formed and never a valid card number.
 */
class CryptoSignerVoucherCodeTest {

    private static final int SAMPLES = 20_000;

    @Test
    void everyCodeIsSixteenDigits_neverStartingWithZero() {
        assertThat(CryptoSigner.CODE_DIGITS).isEqualTo(16);
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(CryptoSigner.randomNumericVoucherCode()).matches("[1-9][0-9]{15}");
        }
    }

    @Test
    void everyCodeHasAValidCheckDigit_andIsNeverACardNumber() {
        // Unconstrained random codes passed Luhn about 1 time in 10, and ~2.3%
        // landed in a card-issuer range as well — flagged by card scanners.
        for (int i = 0; i < SAMPLES; i++) {
            String code = CryptoSigner.randomNumericVoucherCode();
            assertThat(VoucherCodes.isWellFormedNumeric(code)).as(code).isTrue();
            assertThat(luhnValid(code)).as(code).isFalse();
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
        for (int pos = 1; pos < CryptoSigner.CODE_DIGITS; pos++) {
            for (char d = '0'; d <= '9'; d++) {
                assertThat(seen).contains(pos + ":" + d);
            }
        }
    }

    @Test
    void codesDoNotRepeatInPractice() {
        // 9×10¹⁴ codes: 20k draws expect effectively no collisions, so any
        // repeat beyond a handful means the generator is not using the space.
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            codes.add(CryptoSigner.randomNumericVoucherCode());
        }
        assertThat(codes).hasSizeGreaterThan(SAMPLES - 5);
    }

    private static boolean luhnValid(String number) {
        int sum = 0;
        for (int i = 0; i < number.length(); i++) {
            int d = number.charAt(number.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }
}

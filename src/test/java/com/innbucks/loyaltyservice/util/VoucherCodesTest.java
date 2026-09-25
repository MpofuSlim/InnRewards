package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A code is shown grouped and typed back grouped, so the rendering and the
 * normalising must be exact inverses; the check digit must catch typos and
 * keep every code from passing as a payment-card number.
 */
class VoucherCodesTest {

    /** A real, check-digit-valid code: 908787659876456 + check digit 6. */
    private static final String CODE = "9087876598764566";

    // ------------------------------------------------------------------ display

    @Test
    void display_groupsInFoursWithSpaces() {
        assertThat(VoucherCodes.display(CODE)).isEqualTo("9087 8765 9876 4566");
    }

    @Test
    void display_groupsALegacyAlphanumericCodeTheSameWay() {
        assertThat(VoucherCodes.display("K7M2PQ9XR4TB")).isEqualTo("K7M2 PQ9X R4TB");
    }

    @Test
    void display_leavesAShapeThisServiceNeverMintedUntouched() {
        // A stored code with a hyphen or lower case (pre-extraction or hand-made
        // rows) must print as stored — regrouping it would print a code that no
        // longer matches the row.
        assertThat(VoucherCodes.display("VCH-AB12CD34")).isEqualTo("VCH-AB12CD34");
        assertThat(VoucherCodes.display("abc12345")).isEqualTo("abc12345");
    }

    @Test
    void display_isIdempotent() {
        assertThat(VoucherCodes.display(VoucherCodes.display(CODE))).isEqualTo("9087 8765 9876 4566");
    }

    @Test
    void forExport_groupsWithHyphens() {
        // Spaces are a digit-grouping symbol in some locales (en-ZA, fr-FR), so
        // a space-grouped cell could still parse as a number and lose its last
        // digit; no locale groups digits with a hyphen.
        assertThat(VoucherCodes.forExport(CODE)).isEqualTo("9087-8765-9876-4566");
    }

    // ---------------------------------------------------------------- normalize

    @Test
    void normalize_undoesDisplayAndExport() {
        assertThat(VoucherCodes.normalize(VoucherCodes.display(CODE))).isEqualTo(CODE);
        assertThat(VoucherCodes.normalize(VoucherCodes.forExport(CODE))).isEqualTo(CODE);
        assertThat(VoucherCodes.normalize(VoucherCodes.display("K7M2PQ9XR4TB"))).isEqualTo("K7M2PQ9XR4TB");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9087876598764566",
            "9087 8765 9876 4566",
            " 9087 8765 9876 4566 ",
            "9087-8765-9876-4566",
            "9087  8765\t9876\n4566",
            "9087 8765 9876 4566",    // NO-BREAK SPACE (WhatsApp / spreadsheet paste)
            "9087–8765—9876‐4566",    // en dash, em dash, hyphen
            "9087−8765−9876−4566",    // MINUS SIGN
            "﻿9087​8765­9876‎4566", // BOM, zero-width space, soft hyphen, LRM
    })
    void normalize_acceptsEveryWayAPersonTypesOrPastesIt(String typed) {
        assertThat(VoucherCodes.normalize(typed)).isEqualTo(CODE);
    }

    @Test
    void normalize_upperCasesALegacyCodeTypedInLowerCase() {
        assertThat(VoucherCodes.normalize("k7m2 pq9x r4tb")).isEqualTo("K7M2PQ9XR4TB");
    }

    @Test
    void normalize_upperCasesAsciiOnly_soTheResultIsNeverLongerThanTheInput() {
        // Full Unicode upper-casing turns "ß" into "SS" and "ﬃ" into "FFI" — a
        // 64-char request would then overflow the VARCHAR(64) fraud column.
        String hostile = "ß".repeat(32) + "ﬃ".repeat(32);
        assertThat(VoucherCodes.normalize(hostile)).hasSizeLessThanOrEqualTo(hostile.length());
        assertThat(VoucherCodes.normalize("ßﬃ")).isEqualTo("ßﬃ");
    }

    @Test
    void normalize_correctsNothingElse() {
        // A mistyped character must MISS, never be coerced into another code.
        assertThat(VoucherCodes.normalize("9087 8765 9876 456O")).isEqualTo("908787659876456O");
    }

    @Test
    void nullStaysNull() {
        assertThat(VoucherCodes.normalize(null)).isNull();
        assertThat(VoucherCodes.display(null)).isNull();
        assertThat(VoucherCodes.forExport(null)).isNull();
    }

    // -------------------------------------------------------------- check digit

    @Test
    void wellFormed_acceptsAnIssuableCode() {
        assertThat(VoucherCodes.isWellFormedNumeric(CODE)).isTrue();
    }

    @Test
    void wellFormed_catchesEverySingleDigitTypo() {
        for (int pos = 0; pos < CODE.length(); pos++) {
            for (char d = '0'; d <= '9'; d++) {
                if (d == CODE.charAt(pos)) continue;
                String typo = CODE.substring(0, pos) + d + CODE.substring(pos + 1);
                assertThat(VoucherCodes.isWellFormedNumeric(typo))
                        .as("typo at %d -> %s", pos, typo).isFalse();
            }
        }
    }

    @Test
    void wellFormed_catchesAdjacentSwaps_exceptTheLuhnBlindSpot() {
        for (int pos = 0; pos < CODE.length() - 1; pos++) {
            char a = CODE.charAt(pos), b = CODE.charAt(pos + 1);
            if (a == b) continue;
            boolean blindSpot = (a == '0' && b == '9') || (a == '9' && b == '0');
            String swapped = CODE.substring(0, pos) + b + a + CODE.substring(pos + 2);
            if (!blindSpot) {
                assertThat(VoucherCodes.isWellFormedNumeric(swapped)).as("swap at %d", pos).isFalse();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "908787659876456", "90878765987645660", "0087876598764566",
            "9087 8765 9876 4566", "K7M2PQ9XR4TB", "90878765987645a6"})
    void wellFormed_refusesWrongShapes(String candidate) {
        assertThat(VoucherCodes.isWellFormedNumeric(candidate)).isFalse();
    }

    @Test
    void wellFormed_nullIsFalse() {
        assertThat(VoucherCodes.isWellFormedNumeric(null)).isFalse();
    }

    @Test
    void anIssuableCodeIsNeverAValidCardNumber() {
        // Luhn-valid 16-digit numbers are what card scanners flag. The check
        // digit is the Luhn digit offset by 5, so it can never be the Luhn one.
        assertThat(luhnValid(CODE)).isFalse();
    }

    @Test
    void checkDigit_refusesAWrongLengthPayload() {
        assertThatThrownBy(() -> VoucherCodes.checkDigit("123")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Plain Luhn, independent of the implementation under test. */
    static boolean luhnValid(String number) {
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

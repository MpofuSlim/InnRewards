package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A code is shown grouped and typed back grouped, so the two halves must be
 * exact inverses: whatever {@link VoucherCodes#display} prints,
 * {@link VoucherCodes#normalize} must turn back into the stored code.
 */
class VoucherCodesTest {

    private static final String CODE = "9087876598764567";

    @Test
    void display_groupsInFours() {
        assertThat(VoucherCodes.display(CODE)).isEqualTo("9087 8765 9876 4567");
    }

    @Test
    void display_groupsALegacyAlphanumericCodeTheSameWay() {
        assertThat(VoucherCodes.display("K7M2PQ9XR4TB")).isEqualTo("K7M2 PQ9X R4TB");
    }

    @Test
    void display_isIdempotent() {
        assertThat(VoucherCodes.display(VoucherCodes.display(CODE))).isEqualTo("9087 8765 9876 4567");
    }

    @Test
    void normalize_undoesDisplay() {
        assertThat(VoucherCodes.normalize(VoucherCodes.display(CODE))).isEqualTo(CODE);
        assertThat(VoucherCodes.normalize(VoucherCodes.display("K7M2PQ9XR4TB"))).isEqualTo("K7M2PQ9XR4TB");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9087876598764567",
            "9087 8765 9876 4567",
            " 9087 8765 9876 4567 ",
            "9087-8765-9876-4567",
            "9087  8765\t9876\n4567",
            "9087 8765 9876 4567",   // NO-BREAK SPACE, e.g. pasted from WhatsApp
            "9087–8765—9876‐4567",   // en dash, em dash, hyphen
    })
    void normalize_acceptsEveryWayAPersonTypesOrPastesIt(String typed) {
        assertThat(VoucherCodes.normalize(typed)).isEqualTo(CODE);
    }

    @Test
    void normalize_upperCasesALegacyCodeTypedInLowerCase() {
        assertThat(VoucherCodes.normalize("k7m2 pq9x r4tb")).isEqualTo("K7M2PQ9XR4TB");
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
    }
}

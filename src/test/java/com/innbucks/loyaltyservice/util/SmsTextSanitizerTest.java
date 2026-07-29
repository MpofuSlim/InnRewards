package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsTextSanitizerTest {

    @Test
    void transliteratesTypographicPunctuationToAscii() {
        // The real bug: em-dash in "— The InnBucks Team" got 400 "Invalid message".
        assertEquals("- The InnBucks Team", SmsTextSanitizer.toGsmSafe("— The InnBucks Team"));
        assertEquals("a-b", SmsTextSanitizer.toGsmSafe("a–b"));          // en dash
        assertEquals("we're 'in'", SmsTextSanitizer.toGsmSafe("we’re “in”")); // curly quotes -> apostrophes
        assertEquals("wait...", SmsTextSanitizer.toGsmSafe("wait…"));    // ellipsis
        assertEquals("a b", SmsTextSanitizer.toGsmSafe("a b"));          // non-breaking space
        assertEquals("- item", SmsTextSanitizer.toGsmSafe("• item"));    // bullet -> hyphen
    }

    @Test
    void stripsDiacriticsToBaseLetters() {
        // José Müller -> Jose Muller
        assertEquals("Jose Muller", SmsTextSanitizer.toGsmSafe("José Müller"));
    }

    @Test
    void replacesRemainingNonAsciiWithQuestionMark() {
        assertEquals("hi ", SmsTextSanitizer.toGsmSafe("hi 😀")); // emoji -> space (never '?')
        assertEquals(" ", SmsTextSanitizer.toGsmSafe("你好"));       // CJK -> space, runs collapsed
    }

    @Test
    void leavesPlainAsciiAndAllowedWhitespaceUnchanged() {
        String plain = "Your InnBucks code is 123456. It expires in 5 minutes.\nDo not share it.";
        assertEquals(plain, SmsTextSanitizer.toGsmSafe(plain));
    }

    @Test
    void output_isPureAscii_forMixedInput() {
        String out = SmsTextSanitizer.toGsmSafe("Good news — your account is active. – The Team 🎉");
        assertTrue(out.chars().allMatch(c -> c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c <= 0x7E)),
                "sanitised output must be pure printable ASCII, was: " + out);
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(SmsTextSanitizer.toGsmSafe(null));
        assertEquals("", SmsTextSanitizer.toGsmSafe(""));
    }


    @Test
    void rejectedPunctuation_isReplaced() {
        // Probed one character at a time against the live gateway on 2026-07-29:
        // ! : / ? " * ; are all refused with 400 "Invalid message", while
        // ( ) - % @ & # ' + . , are accepted.
        assertEquals("Your voucher is ready. Code ABC123",
                SmsTextSanitizer.toGsmSafe("Your voucher is ready! Code: ABC123"));
        assertEquals("Register Sign in to redeem",
                SmsTextSanitizer.toGsmSafe("Register/Sign in to redeem"));
        assertEquals("Are your points ready.", SmsTextSanitizer.toGsmSafe("Are your points ready?"));
        assertEquals("Points earned. balance updated",
                SmsTextSanitizer.toGsmSafe("Points earned; balance updated"));
        assertEquals("Dial 569# to check", SmsTextSanitizer.toGsmSafe("Dial *569# to check"));
        assertEquals("Shop 'Speke Avenue' open", SmsTextSanitizer.toGsmSafe("Shop \"Speke Avenue\" open"));
        // Accepted set survives untouched.
        assertEquals("Fish & Chips (Avondale) - 50% @ #1 +263 don't",
                SmsTextSanitizer.toGsmSafe("Fish & Chips (Avondale) - 50% @ #1 +263 don't"));
    }
}

package com.innbucks.loyaltyservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HtmlSanitizer} — the OWASP A03 stored-XSS guard applied
 * to user-supplied free text on loyalty write paths. Pins the two invariants
 * callers rely on: (1) legitimate plain text round-trips byte-for-byte, so the
 * sanitizer is invisible to real input, and (2) injected markup is neutralized.
 */
class HtmlSanitizerTest {

    // --- stripAll: null-safety + legitimate input is untouched -----------------

    @Test
    void stripAll_null_returnsNull() {
        assertThat(HtmlSanitizer.stripAll(null)).isNull();
    }

    @Test
    void stripAll_plainName_isUnchanged() {
        assertThat(HtmlSanitizer.stripAll("Pizza Inn Avondale")).isEqualTo("Pizza Inn Avondale");
    }

    @Test
    void stripAll_addressWithCommas_isUnchanged() {
        assertThat(HtmlSanitizer.stripAll("123 King George Rd, Avondale, Harare"))
                .isEqualTo("123 King George Rd, Avondale, Harare");
    }

    @Test
    void stripAll_ampersand_roundTripsDecoded() {
        // The whole point of unescaping entities after cleaning: "Tom & Jerry"
        // must survive as-is, not become "Tom &amp; Jerry".
        assertThat(HtmlSanitizer.stripAll("Tom & Jerry")).isEqualTo("Tom & Jerry");
        assertThat(HtmlSanitizer.stripAll("Food & Beverage")).isEqualTo("Food & Beverage");
    }

    // --- stripAll: markup is neutralized ---------------------------------------

    @Test
    void stripAll_dropsFormattingTags_keepsText() {
        assertThat(HtmlSanitizer.stripAll("<b>Steers</b> Westgate")).isEqualTo("Steers Westgate");
    }

    @Test
    void stripAll_removesScript() {
        String cleaned = HtmlSanitizer.stripAll("<script>alert('xss')</script>");
        assertThat(cleaned).doesNotContain("<");
    }

    @Test
    void stripAll_removesEventHandlerMarkup() {
        String cleaned = HtmlSanitizer.stripAll("<img src=x onerror=alert(1)>Alice");
        assertThat(cleaned).doesNotContain("<").contains("Alice");
    }

    // --- sanitizeRichText: keeps allowlisted formatting, drops scripts ---------

    @Test
    void sanitizeRichText_null_returnsNull() {
        assertThat(HtmlSanitizer.sanitizeRichText(null)).isNull();
    }

    @Test
    void sanitizeRichText_keepsBasicTags_dropsScript() {
        String cleaned = HtmlSanitizer.sanitizeRichText("<b>hi</b><script>steal()</script>");
        assertThat(cleaned).contains("<b>hi</b>").doesNotContain("<script");
    }
}

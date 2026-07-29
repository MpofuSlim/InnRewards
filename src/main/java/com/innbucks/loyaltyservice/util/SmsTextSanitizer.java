package com.innbucks.loyaltyservice.util;

import java.text.Normalizer;
import org.jspecify.annotations.Nullable;

/**
 * Makes SMS text safe for the InnBucks notification API / SMS gateway, which
 * rejects non-GSM / non-ASCII characters with {@code 400 "Invalid message"}
 * (confirmed live — an em-dash in the body is rejected, while the identical
 * ASCII text is accepted).
 *
 * <p>Transactional copy picks up typographic punctuation (em/en dashes, curly
 * quotes, ellipsis, non-breaking space, bullet) such as the "- The InnBucks
 * Team" sign-offs (written with an em-dash) which the gateway rejects. This
 * transliterates those to their ASCII equivalents, strips diacritics
 * (accented letter to its base letter), and replaces anything still outside
 * printable ASCII with {@code '?'} so the gateway never sees a byte it rejects.
 *
 * <p><b>SMS only.</b> Apply this on the SMS send path exclusively — email and
 * WhatsApp render Unicode fine and keep their original typography.
 */
public final class SmsTextSanitizer {

    private SmsTextSanitizer() {
    }

    /**
     * Returns a GSM/ASCII-safe form of {@code text}. Null/blank pass through
     * unchanged (the callers already reject blank before sending).
     */
    public static @Nullable String toGsmSafe(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text
                .replace("–", "-").replace("—", "-")   // en dash / em dash
                .replace("‒", "-").replace("―", "-")   // figure dash / horizontal bar
                .replace("‘", "'").replace("’", "'")   // curly single quotes / apostrophe
                .replace("‚", "'").replace("‛", "'")
                .replace("“", "\"").replace("”", "\"") // curly double quotes
                .replace("„", "\"")
                .replace("…", "...")                          // ellipsis
                .replace(" ", " ")                            // non-breaking space
                .replace("•", "*").replace("·", ".")    // bullet / middle dot
                // The gateway rejects "!" outright with 400 "Invalid message" —
                // proved live 2026-07-29 by bisection: a 160-char message passes,
                // a 24-char one containing a single "!" is refused, and the same
                // text with "." instead is accepted. It is valid GSM-7, so this
                // is a quirk of THIS API, which is exactly what this class exists
                // to absorb. Every voucher-ready, points-unlocked and expiry
                // message carried one, so they were all silently failing over to
                // another channel.
                .replace("!", ".");
        // Strip diacritics (accented -> base letter) so accented copy degrades to
        // GSM-safe ASCII rather than being replaced wholesale by the net below.
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        // Final safety net: anything still outside printable ASCII (keep the
        // whitespace GSM-7 allows: tab / newline / carriage return) becomes '?'.
        // Iterate by CODE POINT, not char, so a supplementary character (e.g. an
        // emoji, which is a UTF-16 surrogate pair) collapses to a single '?'
        // rather than one '?' per surrogate half.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.append((cp == '\n' || cp == '\r' || cp == '\t' || (cp >= 0x20 && cp <= 0x7E))
                    ? (char) cp : '?');
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}

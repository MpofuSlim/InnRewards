package com.innbucks.loyaltyservice.util;

import java.util.Locale;

/**
 * The two rules for a voucher code that is read or typed by a PERSON — the one
 * place both live, so no call site invents its own.
 *
 * <p>A code is STORED raw — {@code 9087876598764567}, or a legacy
 * {@code K7M2PQ9XR4TB} — and every machine-facing surface (API JSON, the
 * signature payload, QR payloads, S2S) carries it raw. Only what a human reads
 * is grouped, and only what a human typed is normalised.
 */
public final class VoucherCodes {

    private static final int GROUP = 4;

    private VoucherCodes() {
    }

    /**
     * The canonical form to look a code up by: every separator a person might
     * type or paste removed, letters upper-cased.
     *
     * <p>Grouped display ({@link #display}) is only safe because of this: a
     * customer reads {@code 9087 8765 9876 4567} off a message and a cashier
     * types exactly that, spaces included. Separators stripped are whitespace
     * of any kind — including the NO-BREAK SPACE a copy from WhatsApp or a
     * spreadsheet can carry, which {@link Character#isWhitespace} does NOT
     * report — and hyphens/dashes of any kind. Upper-casing serves the legacy
     * alphanumeric codes, which were issued upper-case; digits are unaffected.
     *
     * <p>Nothing else is rewritten: a stray letter stays and the lookup simply
     * misses, rather than being "corrected" into some other voucher's code.
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || Character.getType(c) == Character.DASH_PUNCTUATION) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * The code as a PERSON should see it: groups of four separated by single
     * spaces — {@code 9087 8765 9876 4567}, or {@code K7M2 PQ9X R4TB} for a
     * legacy code. Fewer mistakes reading it aloud or typing it back.
     *
     * <p>For human-facing text ONLY (messages, exports, receipts). Never put it
     * in an API field, a signature payload or a QR: machines get the raw code.
     *
     * <p>It also keeps a code intact in a spreadsheet. Excel holds numbers to 15
     * significant digits, so an ungrouped 16-digit code opened from a CSV is
     * silently CORRUPTED — {@code 9087876598764567} becomes
     * {@code 9087876598764560}, a code that never redeems. With spaces in it,
     * the cell is text and survives untouched.
     */
    public static String display(String code) {
        if (code == null) {
            return null;
        }
        String raw = normalize(code);
        StringBuilder sb = new StringBuilder(raw.length() + raw.length() / GROUP);
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                sb.append(' ');
            }
            sb.append(raw.charAt(i));
        }
        return sb.toString();
    }
}

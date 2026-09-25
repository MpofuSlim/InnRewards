package com.innbucks.loyaltyservice.util;

/**
 * The rules for a voucher code that a PERSON reads or types — the one place
 * they live, so no call site invents its own.
 *
 * <p><b>Raw at rest and on every machine surface.</b> A code is stored raw —
 * {@code 9087876598764566}, or a legacy {@code K7M2PQ9XR4TB} — and the API
 * JSON, the HMAC signature payload and every S2S body carry it raw. Only text
 * a human reads is grouped ({@link #display}, {@link #forExport}), and only
 * text a human typed is normalised ({@link #normalize}) before a lookup.
 * Never run any of this on a tenant code, a {@code VCH-} purchase-order
 * reference or a QR token: those are different identifiers and the rules
 * would break them.
 *
 * <p><b>The 16-digit format.</b> First digit 1–9 (a spreadsheet drops a
 * leading zero), fourteen random digits, then a {@linkplain #checkDigit check
 * digit}. The check digit catches every single mistyped digit and nearly every
 * swapped pair, and it is offset so that no code is ever a valid payment-card
 * number (see {@link #checkDigit}).
 */
public final class VoucherCodes {

    /** Length of a numeric voucher code, check digit included. */
    public static final int NUMERIC_LENGTH = 16;

    private static final int GROUP = 4;

    /**
     * Added to the Luhn check digit. Any value 1–9 makes every code fail the
     * Luhn test used to recognise card numbers; 5 is simply the one chosen.
     * Changing it invalidates every issued code's check digit — never do.
     */
    private static final int LUHN_OFFSET = 5;

    private VoucherCodes() {
    }

    /**
     * The canonical form to look a code up by: every separator a person might
     * type or paste removed, ASCII letters upper-cased.
     *
     * <p>Stripped: whitespace of any kind — including the NO-BREAK SPACE a copy
     * from WhatsApp or a spreadsheet can carry, which
     * {@link Character#isWhitespace} does NOT report; dashes of any kind plus
     * the MINUS SIGN; and invisible format characters (zero-width space, soft
     * hyphen, direction marks, byte-order mark) that a paste can smuggle in.
     *
     * <p><b>Only ASCII {@code a–z} is upper-cased</b>, deliberately not
     * {@link String#toUpperCase}: full Unicode case mapping can LENGTHEN a
     * string ({@code ß} → {@code SS}, {@code ﬃ} → {@code FFI}), which would let
     * a request inside its {@code @Size(max = 64)} bound produce a normalised
     * value that overflows the {@code VARCHAR(64)} it is recorded in. With this
     * rule the result is never longer than the input.
     *
     * <p>Nothing else is rewritten: a stray character stays and the lookup
     * simply misses, rather than being "corrected" into another voucher's code.
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.DASH_PUNCTUATION || type == Character.FORMAT || c == '−') {
                continue;
            }
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /**
     * The code as a PERSON should read it in a message: groups of four
     * separated by single spaces — {@code 9087 8765 9876 4566}, or
     * {@code K7M2 PQ9X R4TB} for a legacy code.
     *
     * <p>A code containing anything but {@code A–Z}/{@code 0–9} is returned
     * UNCHANGED rather than regrouped: it is not a shape this service ever
     * minted, and reflowing it would print something that no longer matches
     * the stored value. Idempotent for the same reason.
     *
     * <p>For human-facing text ONLY. Never put it in an API field, a signature
     * payload or a QR: machines get the raw code.
     */
    public static String display(String code) {
        return group(code, ' ');
    }

    /**
     * The code as it goes into a CSV/spreadsheet export: groups of four joined
     * by hyphens — {@code 9087-8765-9876-4566}.
     *
     * <p>Not spaces, and not raw. Spreadsheets hold 15 significant digits, so a
     * RAW 16-digit code opened from a CSV is silently corrupted — its last digit
     * becomes 0 and it never redeems. A space-grouped code is not safe either:
     * locales such as en-ZA and fr-FR use a space as the digit-grouping
     * symbol, so a lenient parse can still read it as a number. No locale
     * groups digits with a hyphen, so the cell stays text; and
     * {@link #normalize} strips hyphens, so a code copied out of the sheet
     * still redeems.
     */
    public static String forExport(String code) {
        return group(code, '-');
    }

    private static String group(String code, char separator) {
        if (code == null || !isPlainAlphanumeric(code)) {
            return code;
        }
        StringBuilder sb = new StringBuilder(code.length() + code.length() / GROUP);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                sb.append(separator);
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    private static boolean isPlainAlphanumeric(String code) {
        if (code.isEmpty()) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The check digit for the first fifteen digits of a numeric code: the Luhn
     * check digit plus {@value #LUHN_OFFSET}, mod 10.
     *
     * <p><b>Why Luhn, offset.</b> Luhn detects every single-digit error and
     * every adjacent transposition except 09↔90, so a code with one digit
     * mistyped fails the check — a till can say "that code is mistyped" instead
     * of "no such voucher". The offset guarantees the full code FAILS the plain
     * Luhn test, because a 16-digit number that passes it in a card-issuer
     * range is, to every card-number scanner (PCI/DLP tools in mail, file
     * shares, messaging gateways), a live card number: an export gets
     * quarantined and a code gets masked in a message. Measured: about 2.3% of
     * unconstrained random codes did.
     *
     * @param firstFifteen exactly 15 ASCII digits
     */
    public static int checkDigit(CharSequence firstFifteen) {
        if (firstFifteen.length() != NUMERIC_LENGTH - 1) {
            throw new IllegalArgumentException("expected " + (NUMERIC_LENGTH - 1) + " digits");
        }
        int sum = 0;
        // Luhn over the payload as it will sit in the 16-digit number: the
        // rightmost payload digit is the first one doubled.
        for (int i = 0; i < firstFifteen.length(); i++) {
            int d = firstFifteen.charAt(firstFifteen.length() - 1 - i) - '0';
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("digits only");
            }
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        int luhn = (10 - (sum % 10)) % 10;
        return (luhn + LUHN_OFFSET) % 10;
    }

    /**
     * True for a 16-digit numeric code whose check digit is right — i.e. one
     * this service could have issued. Says nothing about whether the voucher
     * EXISTS. Legacy alphanumeric codes are not numeric codes and return false;
     * callers must not treat that as a typo.
     */
    public static boolean isWellFormedNumeric(String canonical) {
        if (canonical == null || canonical.length() != NUMERIC_LENGTH) {
            return false;
        }
        for (int i = 0; i < NUMERIC_LENGTH; i++) {
            char c = canonical.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        if (canonical.charAt(0) == '0') {
            return false;
        }
        return checkDigit(canonical.subSequence(0, NUMERIC_LENGTH - 1)) == canonical.charAt(NUMERIC_LENGTH - 1) - '0';
    }
}

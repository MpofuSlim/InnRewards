package com.innbucks.loyaltyservice.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/**
 * Centralized HTML sanitization for user-supplied free text (OWASP A03 / stored-XSS).
 *
 * <p>{@link #stripAll} removes ALL markup — for plain-text fields (names, addresses,
 * categories, reasons). Legitimate input never contains HTML, so this is invisible
 * to callers; it only neutralizes injected tags. HTML entities are decoded so values
 * like "Tom &amp; Jerry" round-trip unchanged.
 *
 * <p>{@link #sanitizeRichText} keeps common formatting while dropping scripts, event
 * handlers, style/class attributes and javascript: URLs (unused here today but kept
 * for parity with the other services).
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {}

    private static final Safelist RICH_TEXT = Safelist.basic()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6");

    private static final Document.OutputSettings NO_PRETTY =
            new Document.OutputSettings().prettyPrint(false);

    /** Strip all HTML; for plain-text fields. Null-safe. */
    public static String stripAll(String input) {
        if (input == null) {
            return null;
        }
        return Parser.unescapeEntities(Jsoup.clean(input, "", Safelist.none(), NO_PRETTY), false);
    }

    /** Allowlist-sanitize rich text. Null-safe. */
    public static String sanitizeRichText(String input) {
        if (input == null) {
            return null;
        }
        return Jsoup.clean(input, "", RICH_TEXT, NO_PRETTY);
    }
}

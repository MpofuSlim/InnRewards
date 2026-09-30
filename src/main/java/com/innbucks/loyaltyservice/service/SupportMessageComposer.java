package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.SupportMessage;
import com.innbucks.loyaltyservice.util.SmsTextSanitizer;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a support message will actually say, per channel — pure, no I/O, so the
 * preview and the send cannot disagree about it.
 *
 * <ul>
 *   <li><b>SMS</b>: body + newline + signature, through the same
 *       {@link SmsTextSanitizer} the SMS client applies. That is what the phone
 *       receives, so it is what is measured and stored. Note the sanitiser
 *       turns {@code :} and {@code /} into spaces (the gateway refuses both),
 *       so a full URL does not survive SMS — the preview shows the agent that.</li>
 *   <li><b>WhatsApp</b>: body + newline + signature, untouched.</li>
 *   <li><b>SMS segments</b>: the sanitised text is plain GSM-7 basic-alphabet
 *       ASCII (no extension characters survive the sanitiser), so it is 160
 *       characters in one segment, 153 per segment once concatenated.</li>
 * </ul>
 *
 * <p><b>Links</b> are the one content rule: an agent types free text to a
 * customer on the platform's own number, and a link to anywhere but our own
 * domain is the shape of a phishing message. A URL-like token —
 * {@code http://}, {@code https://}, {@code www.}, or {@code host.tld/...} —
 * must point at an allowed host or a subdomain of one. Checked on the text the
 * agent typed, NFKC-normalised first so full-width look-alike characters cannot
 * dress a link up as plain text. A bare {@code host.tld} with no scheme,
 * {@code www.} or path is NOT detected — the shared contract's definition,
 * deliberately matched to marketplace's.
 */
public final class SupportMessageComposer {

    private SupportMessageComposer() {}

    static final int SMS_SINGLE_SEGMENT = 160;
    static final int SMS_MULTIPART_SEGMENT = 153;

    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)(?:https?://\\S*"
                    + "|\\bwww\\.\\S+"
                    + "|\\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}/\\S*)");

    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}'\">";

    /** The per-channel renderings of one message. */
    public record Rendered(String smsText, String whatsappText, boolean transliterated) {

        /** The text that goes out FIRST on {@code channel} — what the preview shows. */
        public String primaryText(SupportMessage.Channel channel) {
            return channel == SupportMessage.Channel.WHATSAPP ? whatsappText : smsText;
        }
    }

    /** Body + newline + signature, and its SMS form. */
    public static Rendered withSignature(String body, String signature) {
        String text = signature == null || signature.isBlank() ? body : body + "\n" + signature;
        return render(text);
    }

    /** A fixed template (the voucher resend) — no signature, same per-channel rendering. */
    public static Rendered render(String text) {
        String sms = SmsTextSanitizer.toGsmSafe(text);
        return new Rendered(sms, text, !text.equals(sms));
    }

    public static int smsSegments(String smsText) {
        int length = smsText == null ? 0 : smsText.length();
        if (length <= SMS_SINGLE_SEGMENT) {
            return 1;
        }
        return (length + SMS_MULTIPART_SEGMENT - 1) / SMS_MULTIPART_SEGMENT;
    }

    /**
     * The first link host in {@code text} that is not on {@code allowedHosts}
     * (nor a subdomain of one), or empty when every link is allowed.
     */
    public static Optional<String> firstDisallowedHost(String text, List<String> allowedHosts) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = URL_LIKE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (m.find()) {
            String host = hostOf(m.group());
            if (!allowed(host, allowedHosts)) {
                return Optional.of(host.isEmpty() ? "(no host)" : host);
            }
        }
        return Optional.empty();
    }

    /**
     * The host a link actually goes to. For a scheme URL that is the authority
     * minus any {@code user@} prefix and port — so
     * {@code https://innbucks.co.zw@evil.example/} is {@code evil.example}, which
     * is what a browser would open.
     */
    static String hostOf(String token) {
        String t = token;
        String lower = t.toLowerCase(Locale.ROOT);
        String authority;
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String rest = t.substring(t.indexOf("//") + 2);
            authority = cut(rest, "/?#\\");
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
            }
        } else {
            authority = cut(t, "/?#\\");
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return stripTrailing(authority).toLowerCase(Locale.ROOT);
    }

    static boolean allowed(String host, List<String> allowedHosts) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String cut(String s, String stops) {
        for (int i = 0; i < s.length(); i++) {
            if (stops.indexOf(s.charAt(i)) >= 0) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}

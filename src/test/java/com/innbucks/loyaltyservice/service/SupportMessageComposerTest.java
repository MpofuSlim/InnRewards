package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.SupportMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a support message will say, pure: the signature, the SMS
 * transliteration, the segment count and the link allow-list. The preview and
 * the send both go through this, which is what stops them disagreeing.
 */
class SupportMessageComposerTest {

    private static final List<String> ALLOWED = List.of("innbucks.co.zw");

    @Test
    @DisplayName("the signature goes on its own line, and WhatsApp keeps the text as typed")
    void signature_onItsOwnLine() {
        var r = SupportMessageComposer.withSignature("Hello — your refund is done!", "- InnBucks Loyalty Support");

        assertThat(r.whatsappText()).isEqualTo("Hello — your refund is done!\n- InnBucks Loyalty Support");
        assertThat(r.primaryText(SupportMessage.Channel.WHATSAPP)).isEqualTo(r.whatsappText());
    }

    @Test
    @DisplayName("SMS is transliterated by the gateway's own sanitiser, and says so")
    void sms_isTransliterated() {
        var r = SupportMessageComposer.withSignature("Hello — your refund is done!", "- InnBucks Loyalty Support");

        // em-dash -> '-', '!' -> '.': the characters the gateway refuses.
        assertThat(r.smsText()).isEqualTo("Hello - your refund is done.\n- InnBucks Loyalty Support");
        assertThat(r.transliterated()).isTrue();
        assertThat(r.primaryText(SupportMessage.Channel.SMS_THEN_WHATSAPP)).isEqualTo(r.smsText());
    }

    @Test
    @DisplayName("plain GSM text is not reported as transliterated")
    void plainText_notTransliterated() {
        assertThat(SupportMessageComposer.withSignature("Your points are back", "- InnBucks Loyalty Support")
                .transliterated()).isFalse();
    }

    @Test
    @DisplayName("a URL does not survive SMS: ':' and '/' are refused by the gateway, so the preview shows spaces")
    void url_isMangledOnSms() {
        var r = SupportMessageComposer.withSignature("See https://innbucks.co.zw/help", "- S");

        assertThat(r.smsText()).startsWith("See https innbucks.co.zw help");
        assertThat(r.whatsappText()).startsWith("See https://innbucks.co.zw/help");
    }

    @Test
    @DisplayName("segments: 160 in one, then 153 per segment — 459 is exactly three")
    void segments() {
        assertThat(SupportMessageComposer.smsSegments("a".repeat(1))).isEqualTo(1);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(160))).isEqualTo(1);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(161))).isEqualTo(2);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(306))).isEqualTo(2);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(307))).isEqualTo(3);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(459))).isEqualTo(3);
        assertThat(SupportMessageComposer.smsSegments("a".repeat(460))).isEqualTo(4);
    }

    /**
     * The shared contract's REFUSE list, verbatim — marketplace pins the same
     * strings, so one console refuses the same text on both services.
     */
    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {
            "Pay at https://bit.ly/x",
            "see www.evil.example now",
            "go to evil.example",
            "email refunds@gmail.com",
            "http://10.0.0.1/pay",
            "open 10.0.0.1/pay",
            "https://innbucks.co.zw@evil.example/x",
            "innbucks.co.zw.evil.example/x",
            "innbucks.c\u043e.zw/track",   // Cyrillic o: SEEN as a host, and refused
            "Thanks.Your order is ready"     // the accepted false positive
    })
    void refused(String text) {
        assertThat(SupportMessageComposer.firstDisallowedHost(text, ALLOWED)).isPresent();
    }

    /** The shared contract's PASS list, verbatim. */
    @ParameterizedTest(name = "passes: {0}")
    @ValueSource(strings = {
            "Track at https://innbucks.co.zw/track",
            "See www.innbucks.co.zw.",
            "shop.innbucks.co.zw/deals",
            "Write to support@innbucks.co.zw",
            "Your order MKT-8B3E5D7F9A1C costs USD 25.99, e.g. today.",
            "Collect after 3.00pm",
            "It is 1.5kg"
    })
    void passes(String text) {
        assertThat(SupportMessageComposer.firstDisallowedHost(text, ALLOWED)).isEmpty();
    }

    @Test
    @DisplayName("the refused host is the one a browser or mail client would actually reach")
    void theRefusedHostIsTheRealDestination() {
        assertThat(SupportMessageComposer.firstDisallowedHost("email refunds@gmail.com", ALLOWED))
                .contains("gmail.com");
        assertThat(SupportMessageComposer.firstDisallowedHost("https://innbucks.co.zw@evil.example/x", ALLOWED))
                .contains("evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("innbucks.co.zw@evil.example", ALLOWED))
                .contains("evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("open 10.0.0.1/pay", ALLOWED))
                .contains("10.0.0.1");
        assertThat(SupportMessageComposer.firstDisallowedHost("ftp://files.evil.example/a", ALLOWED))
                .contains("files.evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("Thanks.Your order is ready", ALLOWED))
                .contains("thanks.your");
        assertThat(SupportMessageComposer.firstDisallowedHost("ok https://innbucks.co.zw then https://bit.ly/x", ALLOWED))
                .contains("bit.ly");
    }

    @Test
    @DisplayName("look-alikes of the allowed host are refused: suffix tricks and full-width characters")
    void lookAlikes_areRefused() {
        // A different registrable domain that merely ENDS in our name.
        assertThat(SupportMessageComposer.firstDisallowedHost("https://evilinnbucks.co.zw/", ALLOWED))
                .contains("evilinnbucks.co.zw");
        assertThat(SupportMessageComposer.firstDisallowedHost("evilinnbucks.co.zw", ALLOWED))
                .contains("evilinnbucks.co.zw");
        // Full-width letters, NFKC-folded before the check.
        assertThat(SupportMessageComposer.firstDisallowedHost("\uff48\uff54\uff54\uff50\uff53://\uff45\uff56\uff49\uff4c.example/x", ALLOWED))
                .contains("evil.example");
        // A query string that happens to contain a URL does not change the host.
        assertThat(SupportMessageComposer.firstDisallowedHost("evil.example/r?u=https://innbucks.co.zw", ALLOWED))
                .contains("evil.example");
    }

    @Test
    @DisplayName("host extraction drops the port and trailing punctuation")
    void hostOf() {
        assertThat(SupportMessageComposer.hostOf("https://innbucks.co.zw:8443/x")).isEqualTo("innbucks.co.zw");
        assertThat(SupportMessageComposer.hostOf("https://innbucks.co.zw).")).isEqualTo("innbucks.co.zw");
        assertThat(SupportMessageComposer.hostOf("www.innbucks.co.zw,")).isEqualTo("www.innbucks.co.zw");
    }
}

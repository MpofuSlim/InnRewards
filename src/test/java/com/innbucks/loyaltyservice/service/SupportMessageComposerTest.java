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

    @ParameterizedTest
    @ValueSource(strings = {
            "Visit https://innbucks.co.zw/loyalty",
            "Visit http://innbucks.co.zw",
            "Visit https://help.innbucks.co.zw/faq?x=1.",
            "Visit www.innbucks.co.zw today",
            "Visit innbucks.co.zw/vouchers",
            "HTTPS://INNBUCKS.CO.ZW/X",
            "No link here at all, 3.50/month is not one either",
            "Plain innbucks.co.zw with no path is not a link by the contract's definition"
    })
    @DisplayName("allowed hosts, their subdomains, and link-free text pass")
    void allowed(String text) {
        assertThat(SupportMessageComposer.firstDisallowedHost(text, ALLOWED)).isEmpty();
    }

    @Test
    @DisplayName("any other host is named, in every URL-like form")
    void disallowedHosts_areNamed() {
        assertThat(SupportMessageComposer.firstDisallowedHost("Go to https://evil.example/pay", ALLOWED))
                .contains("evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("Go to www.evil.example", ALLOWED))
                .contains("www.evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("Go to evil.example/pay now", ALLOWED))
                .contains("evil.example");
        assertThat(SupportMessageComposer.firstDisallowedHost("ok https://innbucks.co.zw then https://bit.ly/x", ALLOWED))
                .contains("bit.ly");
    }

    @Test
    @DisplayName("look-alikes of the allowed host are refused: suffix tricks, userinfo, and full-width characters")
    void lookAlikes_areRefused() {
        // A different registrable domain that merely ENDS in our name.
        assertThat(SupportMessageComposer.firstDisallowedHost("https://evilinnbucks.co.zw/", ALLOWED))
                .contains("evilinnbucks.co.zw");
        // Our name as a subdomain of someone else's.
        assertThat(SupportMessageComposer.firstDisallowedHost("https://innbucks.co.zw.evil.example/", ALLOWED))
                .contains("innbucks.co.zw.evil.example");
        // userinfo: a browser opens evil.example.
        assertThat(SupportMessageComposer.firstDisallowedHost("https://innbucks.co.zw@evil.example/login", ALLOWED))
                .contains("evil.example");
        // Full-width letters, NFKC-folded before the check.
        assertThat(SupportMessageComposer.firstDisallowedHost("ｈｔｔｐｓ://ｅｖｉｌ.example/x", ALLOWED))
                .contains("evil.example");
        // A scheme with no host at all.
        assertThat(SupportMessageComposer.firstDisallowedHost("click http:// now", ALLOWED))
                .contains("(no host)");
    }

    @Test
    @DisplayName("host extraction drops the port and trailing punctuation")
    void hostOf() {
        assertThat(SupportMessageComposer.hostOf("https://innbucks.co.zw:8443/x")).isEqualTo("innbucks.co.zw");
        assertThat(SupportMessageComposer.hostOf("https://innbucks.co.zw).")).isEqualTo("innbucks.co.zw");
        assertThat(SupportMessageComposer.hostOf("www.innbucks.co.zw,")).isEqualTo("www.innbucks.co.zw");
    }
}

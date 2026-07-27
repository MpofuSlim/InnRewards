package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the branded-HTML rendering contract (see {@link BrandedEmailRenderer}):
 * the body is HTML-escaped (no injection), blank-line paragraphs become
 * {@code <p>}, the footer + disclaimer are always present, and the logo is an
 * {@code <img>} when a URL is given / a CSS fallback when it isn't.
 */
class BrandedEmailRendererTest {

    @Test
    void rendersBodyParagraphsFooterAndDisclaimer() {
        String html = BrandedEmailRenderer.render(
                "Your event is now live",
                "Hello,\n\nYour event \"Feli Nandi\" is now live.\n\nWe hope it's a great event!",
                "https://www.innbucks.co.zw/logo.png");

        // A fragment (bare table), not a full document — safe to inject into the
        // gateway's own HTML body without nesting an <html> element.
        assertThat(html).startsWith("<table");
        assertThat(html).doesNotContain("<html");
        assertThat(html).doesNotContain("<!doctype");
        // Body content survives, split into paragraphs.
        assertThat(html).contains("is now live");
        assertThat(html).contains("<p style=");
        // Standard footer + statutory disclosure always present.
        assertThat(html).contains("The InnBucks Team");
        assertThat(html).contains("Deposit Protection Scheme");
        assertThat(html).contains("+263 (0) 8677 569 569");
    }

    @Test
    void usesHostedLogoImgWhenUrlProvided() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");
        assertThat(html).contains("<img src=\"https://cdn.innbucks.co.zw/logo.png\"");
        assertThat(html).contains("alt=\"InnBucks\"");
    }

    @Test
    void fallsBackToCssLogoWhenUrlBlank() {
        String html = BrandedEmailRenderer.render("s", "body", "");
        assertThat(html).doesNotContain("<img");
        // CSS brand lockup is rendered instead: wordmark + tagline.
        assertThat(html).contains(">InnBucks<");
        assertThat(html).contains(">MicroBank Limited<");
    }

    @Test
    void escapesHtmlInBodySoContentCannotInjectMarkup() {
        String html = BrandedEmailRenderer.render(
                "s", "Body with <b>tags</b> & an ampersand and a <script>alert(1)</script>", "");
        assertThat(html).contains("&lt;b&gt;tags&lt;/b&gt;");
        assertThat(html).contains("&amp; an ampersand");
        // The raw script tag from message content must never appear unescaped.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void escapesQuotesInLogoUrlAttribute() {
        String html = BrandedEmailRenderer.render("s", "b", "https://x/a\"onerror=alert(1)");
        assertThat(html).doesNotContain("\"onerror=alert(1)");
        assertThat(html).contains("&quot;onerror=alert(1)");
    }
}

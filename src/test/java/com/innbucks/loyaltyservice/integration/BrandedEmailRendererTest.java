package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the branded-HTML rendering contract (see {@link BrandedEmailRenderer}):
 * the body is HTML-escaped (no injection), blank-line paragraphs become
 * {@code <p>}, the footer + disclaimer are always present, the logo is an
 * {@code <img>} when a URL is given / a CSS fallback when it isn't, and the
 * header row is navy so the hosted logo's white lettering is legible.
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
        // Still escaped now that the header cell carries the navy background.
        assertThat(html).contains("<img src=\"https://x/a&quot;onerror=alert(1)\"");
    }

    /**
     * The hosted logo asset has WHITE lettering drawn for a dark ground; on the
     * old white header only its four dots were visible (Gmail, staging,
     * 2026-09-29). The header cell must be navy in BOTH forms — {@code bgcolor}
     * for Outlook, inline {@code background} for Gmail — and must be the cell
     * that holds the logo.
     */
    @Test
    void headerCellIsNavySoTheWhiteLetteredLogoIsLegible() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");

        String headerCell = "<td bgcolor=\"#0c2545\" style=\"background:#0c2545;padding:26px 34px 20px;\">";
        assertThat(html).contains(headerCell);
        // The navy cell is the one wrapping the logo: it opens immediately before the <img>.
        assertThat(html).contains(headerCell + "<img src=\"https://cdn.innbucks.co.zw/logo.png\"");
        assertThat(html.indexOf(headerCell)).isLessThan(html.indexOf("<img"));
        // And it comes before the accent bar and the white body — header is still the first row.
        assertThat(html.indexOf(headerCell)).isLessThan(html.indexOf("background:#f5b71c"));
        // The rest of the card is unchanged: white body, navy footer.
        assertThat(html).contains("background:#ffffff;border-radius:12px");
        assertThat(html).contains("<td style=\"background:#0c2545;padding:26px 34px;color:#b9c6d8;");
    }

    @Test
    void cssFallbackLockupIsLightOnTheNavyHeader() {
        String html = BrandedEmailRenderer.render("s", "body", "");

        // The fallback also sits in the navy header cell.
        assertThat(html).contains(
                "<td bgcolor=\"#0c2545\" style=\"background:#0c2545;padding:26px 34px 20px;\"><table");
        // White wordmark, light-slate tagline — not the old navy / grey that
        // would disappear into the navy ground.
        assertThat(html).containsPattern("color:#ffffff;[^\"]*\">InnBucks</div>");
        assertThat(html).containsPattern("color:#b9c6d8;[^\"]*\">MicroBank Limited</div>");
        assertThat(html).doesNotContainPattern("color:#0c2545;[^\"]*\">InnBucks<");
        assertThat(html).doesNotContain("#5d6b7b");
        // The four brand dots keep their colours.
        assertThat(html).contains("background:#f5b71c;width:16px");
        assertThat(html).contains("background:#7a2e8f;width:16px");
        assertThat(html).contains("background:#17a98c;width:16px");
        assertThat(html).contains("background:#e11b22;width:16px");
    }
}

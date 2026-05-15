package com.stucray.limen.auth.ott;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OttEmailHtml renders the plain-text magic-link body as HTML")
class OttEmailHtmlTest {

    @Test
    @DisplayName("The magic-link paragraph is rendered as a real <a href> anchor")
    void magicLinkParagraphBecomesAnchor() {
        String text = "Welcome.\n\nhttps://example.test/t/acme/login/ott?token=abc\n\nExpires in 60 minutes.";
        String html = OttEmailHtml.htmlFromTextBody(text, "https://example.test/t/acme/login/ott?token=abc");

        assertThat(html)
            .contains("<a href=\"https://example.test/t/acme/login/ott?token=abc\">"
                + "https://example.test/t/acme/login/ott?token=abc</a>");
    }

    @Test
    @DisplayName("Non-link paragraphs are wrapped in <p> and HTML-escaped")
    void nonLinkParagraphsAreEscapedAndWrapped() {
        String text = "Hello & welcome to <Test> Co.\n\nhttps://example.test/link\n\nGoodbye.";
        String html = OttEmailHtml.htmlFromTextBody(text, "https://example.test/link");

        assertThat(html)
            .contains("<p>Hello &amp; welcome to &lt;Test&gt; Co.</p>")
            .contains("<p>Goodbye.</p>");
    }

    @Test
    @DisplayName("Output is well-formed HTML with a doctype, charset, and <body>")
    void outputIsWellFormedHtml() {
        String html = OttEmailHtml.htmlFromTextBody(
            "One paragraph.\n\nhttps://example.test", "https://example.test");

        assertThat(html)
            .startsWith("<!DOCTYPE html>")
            .contains("<meta charset=\"UTF-8\">")
            .contains("<body>")
            .contains("</body>")
            .endsWith("</html>\n");
    }

    @Test
    @DisplayName("Empty paragraphs (extra blank lines) are skipped")
    void emptyParagraphsSkipped() {
        String text = "Intro.\n\n\n\nhttps://example.test\n\n\n\nFooter.";
        String html = OttEmailHtml.htmlFromTextBody(text, "https://example.test");

        long paragraphs = html.lines().filter(line -> line.contains("<p>") || line.contains("<p><a")).count();
        assertThat(paragraphs).isEqualTo(3);
    }
}

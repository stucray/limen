package com.stucray.limen.auth.ott;

import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Derives an HTML email body from a plain-text body that follows the magic-link
 * shape: paragraphs separated by blank lines, the magic-link URL on its own
 * paragraph. Used by {@link OttIntentHandler#htmlBody} so each handler keeps a
 * single source of truth for copy in {@link OttIntentHandler#body} and the HTML
 * view is derived.
 *
 * <p>If a future intent needs richer HTML (lists, headings, brand chrome), it
 * can override {@link OttIntentHandler#htmlBody} and build the markup directly.
 */
final class OttEmailHtml {

    private OttEmailHtml() {}

    static String htmlFromTextBody(String textBody, String magicLink) {
        String trimmedLink = magicLink.trim();
        String paragraphs = Arrays.stream(textBody.split("\n\n"))
            .map(String::strip)
            .filter(p -> !p.isEmpty())
            .map(p -> renderParagraph(p, trimmedLink))
            .collect(Collectors.joining("\n  "));

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.5; max-width: 600px; margin: 2em auto; padding: 0 1em; color: #1a1a1a; }
                p { margin: 1em 0; }
                a { color: #0066cc; word-break: break-all; }
              </style>
            </head>
            <body>
              %s
            </body>
            </html>
            """.formatted(paragraphs);
    }

    private static String renderParagraph(String paragraph, String magicLink) {
        if (paragraph.equals(magicLink)) {
            String escapedLink = HtmlUtils.htmlEscape(magicLink);
            return "<p><a href=\"" + escapedLink + "\">" + escapedLink + "</a></p>";
        }
        return "<p>" + HtmlUtils.htmlEscape(paragraph) + "</p>";
    }
}

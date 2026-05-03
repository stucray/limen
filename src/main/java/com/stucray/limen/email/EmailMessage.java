package com.stucray.limen.email;

import org.jspecify.annotations.Nullable;

/**
 * A single outbound email. {@code htmlBody} is optional; when null, the message
 * is sent as plain text only.
 */
public record EmailMessage(
    String recipient,
    String subject,
    String plainTextBody,
    @Nullable String htmlBody
) {
    public EmailMessage(String recipient, String subject, String plainTextBody) {
        this(recipient, subject, plainTextBody, null);
    }
}

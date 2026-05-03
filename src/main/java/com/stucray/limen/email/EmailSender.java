package com.stucray.limen.email;

/**
 * Provider-agnostic outbound email port. Implementation is selected at startup
 * via {@code limen.email.driver} ({@code logging} | {@code smtp}).
 *
 * <p>Callers in upcoming slices (OTT verification, password reset, system-admin
 * tenant create) depend on this interface, not on a concrete provider — the
 * production swap from Mailpit to Resend / SES / etc. is a one-bean
 * configuration change (see roadmap §6 v4).
 */
public interface EmailSender {
    void send(EmailMessage message);
}

package com.stucray.limen.email;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound-email knobs.
 *
 * <ul>
 *   <li>{@code from} — the {@code From:} address every outbound message is
 *       sent with. The base default ({@code no-reply@limen.local}) is the
 *       right shape for {@link LoggingEmailSender} and the Mailpit dev /
 *       integration-test paths, which accept any address. A real SMTP relay
 *       (Resend, Brevo, SES, …) will reject sends from a domain it doesn't
 *       own; override via {@code LIMEN_EMAIL_FROM} per environment so the
 *       From-domain matches whatever has been verified at the provider.</li>
 * </ul>
 *
 * <p>The companion {@code limen.email.driver} key (selecting
 * {@link LoggingEmailSender} vs {@link SmtpEmailSender}) is read directly by
 * {@code @ConditionalOnProperty} on each driver and is intentionally not bound
 * here — driver selection happens at bean-registration time, before
 * {@code @ConfigurationProperties} binding runs.
 */
@ConfigurationProperties("limen.email")
@Validated
public record EmailProperties(@NotBlank String from) {
}

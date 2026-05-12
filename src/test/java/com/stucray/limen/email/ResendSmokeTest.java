package com.stucray.limen.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in real-network smoke test against Resend. Skipped unless both
 * {@code RESEND_SMOKE_API_KEY} and {@code RESEND_SMOKE_RECIPIENT} env vars
 * are set, so CI (which never has those configured) never runs it. The
 * cost is one email per run against your Resend free tier.
 *
 * <p>Run locally with:
 * <pre>
 * RESEND_SMOKE_API_KEY=re_xxxxxxxxxxxx \
 * RESEND_SMOKE_RECIPIENT=your-resend-account@example.com \
 *   mvn test -Dtest=ResendSmokeTest
 * </pre>
 *
 * <p>Activates the {@code resend} Spring profile so the test exercises
 * {@code application-resend.yaml} exactly as production does — host, port,
 * username, and STARTTLS settings come from the profile, not from this
 * test. The two values that vary per environment ({@code limen.email.from}
 * and {@code spring.mail.password}) are overridden inline so the test can
 * fail-fast if env vars are missing rather than hitting {@code @NotBlank}
 * inside the context. {@code onboarding@resend.dev} is the From-address
 * (Resend's test sender, deliverable only to your own Resend-account email)
 * — once your sending domain is DNS-verified, the production
 * {@code LIMEN_EMAIL_FROM} value would replace it.
 *
 * <p>No assertion on body or delivery — success is "the SMTP transaction
 * completed without throwing", which
 * {@link SmtpEmailSender#send(EmailMessage)} guarantees by raising
 * {@code EmailDeliveryException} on any auth, TLS, or transport failure.
 * Verify actual receipt in your inbox after a green run.
 */
@SpringBootTest(
    classes = ResendSmokeTest.Config.class,
    properties = {
        "limen.email.from=onboarding@resend.dev",
        "spring.mail.password=${RESEND_SMOKE_API_KEY}"
    }
)
@ActiveProfiles("resend")
@EnabledIfEnvironmentVariable(named = "RESEND_SMOKE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RESEND_SMOKE_RECIPIENT", matches = ".+")
@DisplayName("ResendSmokeTest sends a real message via smtp.resend.com (opt-in, never runs in CI)")
class ResendSmokeTest {

    @Configuration
    @EnableConfigurationProperties(EmailProperties.class)
    @Import(SmtpEmailSender.class)
    @ImportAutoConfiguration(MailSenderAutoConfiguration.class)
    static class Config {
    }

    @Autowired
    EmailSender emailSender;

    @Test
    @DisplayName("send() completes without exception against smtp.resend.com:587")
    void sendsViaResend() {
        String recipient = System.getenv("RESEND_SMOKE_RECIPIENT");
        emailSender.send(new EmailMessage(
            recipient,
            "Limen Resend smoke test " + System.currentTimeMillis(),
            "If you see this in your inbox, the `resend` profile wiring is working end-to-end."
        ));
    }
}

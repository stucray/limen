package com.stucray.limen.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * SMTP {@link EmailSender}. Activated when {@code limen.email.driver=smtp}.
 * In tests this is wired against a Mailpit Testcontainer; in production it
 * points at a real SMTP host (or a transactional provider's SMTP gateway).
 *
 * <p>If {@link EmailMessage#htmlBody()} is non-null the message is sent as a
 * multipart/alternative with both plain text and HTML; otherwise a plain-text
 * SimpleMailMessage shape is used (still as a MimeMessage so the From header
 * and encoding are explicit).
 */
@Component
@ConditionalOnProperty(name = "limen.email.driver", havingValue = "smtp")
class SmtpEmailSender implements EmailSender {

    private static final String DEFAULT_FROM = "no-reply@limen.local";

    private final JavaMailSender mailSender;

    public SmtpEmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            String html = message.htmlBody();
            boolean multipart = html != null;
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, "UTF-8");
            helper.setFrom(DEFAULT_FROM);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject());
            if (multipart) {
                helper.setText(message.plainTextBody(), Objects.requireNonNull(html));
            } else {
                helper.setText(message.plainTextBody(), false);
            }
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Failed to compose email", e);
        }
        try {
            mailSender.send(mime);
        } catch (MailException e) {
            throw new EmailDeliveryException("Failed to deliver email via SMTP", e);
        }
    }

    public static final class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

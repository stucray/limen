package com.stucray.limen.email;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Wires a {@link MailpitContainer} into the application context and exposes a
 * {@link JavaMailSender} pointing at its ephemeral SMTP port. Imported only by
 * tests that exercise the SMTP path (currently
 * {@code SmtpEmailSenderIntegrationTest}) so the broader IT suite doesn't pay
 * the container startup cost.
 *
 * <p>The {@code JavaMailSender} bean is constructed directly rather than
 * relying on Spring Boot's {@code MailSenderAutoConfiguration} — that
 * auto-configuration evaluates {@code spring.mail.host} at condition time,
 * which is before the dynamic Mailpit port is known.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MailpitTestConfiguration {

    @Bean
    public MailpitContainer mailpitContainer() {
        MailpitContainer container = new MailpitContainer();
        container.start();
        return container;
    }

    @Bean
    public JavaMailSender javaMailSender(MailpitContainer mailpit) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailpit.getHost());
        sender.setPort(mailpit.smtpPort());
        return sender;
    }
}

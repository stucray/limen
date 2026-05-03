package com.stucray.limen.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stucray.limen.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link LoggingEmailSender} writes a single INFO line containing
 * the recipient, subject and body in a parseable form. The default profile is
 * intentionally used: no profile activation, no property overrides — this is
 * the developer-mode shape every contributor sees out of the box.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("LoggingEmailSender writes recipient + subject + body to slf4j INFO")
class LoggingEmailSenderIntegrationTest {

    @Autowired
    EmailSender emailSender;

    private Logger loggingEmailSenderLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        loggingEmailSenderLogger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        appender = new ListAppender<>();
        appender.start();
        loggingEmailSenderLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        loggingEmailSenderLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("Default driver is logging — outbound message lands in the application log")
    void defaultDriverIsLogging() {
        assertThat(emailSender).isInstanceOf(LoggingEmailSender.class);

        emailSender.send(new EmailMessage(
            "alice@example.test",
            "Verify your email",
            "Click https://example.test/verify?token=abc123 to verify."));

        List<ILoggingEvent> infoEvents = appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .toList();
        assertThat(infoEvents).hasSize(1);

        String formatted = infoEvents.get(0).getFormattedMessage();
        assertThat(formatted)
            .contains("alice@example.test")
            .contains("Verify your email")
            .contains("Click https://example.test/verify?token=abc123 to verify.");
    }
}

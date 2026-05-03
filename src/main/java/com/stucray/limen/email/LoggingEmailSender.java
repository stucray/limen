package com.stucray.limen.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSender}. Renders the message to slf4j INFO with a
 * deterministic {@code key=value} structure so a developer can read magic
 * links and verification codes from the application log without running
 * mail infrastructure. Used by the {@code dev} profile and by any deployment
 * where {@code limen.email.driver} is unset or set to {@code logging}.
 */
@Component
@ConditionalOnProperty(name = "limen.email.driver", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("limen.email.send recipient=\"{}\" subject=\"{}\" body=\"{}\"",
            message.recipient(), message.subject(), message.plainTextBody());
    }
}

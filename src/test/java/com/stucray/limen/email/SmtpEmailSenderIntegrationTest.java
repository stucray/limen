package com.stucray.limen.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stucray.limen.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a real outbound message through {@link SmtpEmailSender} into a
 * Mailpit Testcontainer, then retrieves it via Mailpit's HTTP API and asserts
 * recipient / subject / body. No mocking of {@link org.springframework.mail.javamail.JavaMailSender}
 * — the SMTP path is exercised end-to-end so a regression in the MIME building
 * or the Spring auto-configuration is caught at this layer.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MailpitTestConfiguration.class})
@ActiveProfiles("test")
@DisplayName("SmtpEmailSender delivers via Mailpit and Mailpit captures the message")
class SmtpEmailSenderIntegrationTest {

    @Autowired
    EmailSender emailSender;

    @Autowired
    MailpitContainer mailpit;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Plain-text message lands in Mailpit with recipient, subject and body intact")
    void plainTextMessageRoundTripsThroughMailpit() throws Exception {
        assertThat(emailSender).isInstanceOf(SmtpEmailSender.class);

        String subject = "Limen SMTP IT subject " + System.nanoTime();
        String body = "Limen SMTP IT body — magic link https://example.test/verify?token=xyz";
        emailSender.send(new EmailMessage("bob@example.test", subject, body));

        RestClient restClient = RestClient.create(mailpit.httpApiBaseUrl());
        JsonNode messages = waitForOneMessage(restClient, subject);

        JsonNode firstMatch = findBySubject(messages, subject);
        String messageId = firstMatch.get("ID").asText();

        JsonNode full = objectMapper.readTree(
            restClient.get().uri("/api/v1/message/{id}", messageId).retrieve().body(String.class));

        assertThat(full.get("Subject").asText()).isEqualTo(subject);
        assertThat(full.get("Text").asText()).contains(body);
        JsonNode toArray = full.get("To");
        assertThat(toArray).isNotNull();
        assertThat(toArray.get(0).get("Address").asText()).isEqualTo("bob@example.test");
    }

    private JsonNode waitForOneMessage(RestClient restClient, String expectedSubject) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode messages = objectMapper.readTree(
                restClient.get().uri("/api/v1/messages?limit=50").retrieve().body(String.class));
            if (findBySubjectOrNull(messages, expectedSubject) != null) {
                return messages;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for message with subject: " + expectedSubject);
    }

    private static JsonNode findBySubject(JsonNode messages, String subject) {
        JsonNode match = findBySubjectOrNull(messages, subject);
        if (match == null) {
            throw new AssertionError("No message with subject: " + subject);
        }
        return match;
    }

    private static JsonNode findBySubjectOrNull(JsonNode messages, String subject) {
        for (JsonNode m : messages.path("messages")) {
            if (subject.equals(m.path("Subject").asText())) {
                return m;
            }
        }
        return null;
    }
}

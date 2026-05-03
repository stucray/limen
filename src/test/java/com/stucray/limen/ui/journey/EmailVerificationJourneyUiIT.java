package com.stucray.limen.ui.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.email.MailpitContainer;
import com.stucray.limen.email.MailpitTestConfiguration;
import com.stucray.limen.ui.pages.CheckInboxPage;
import com.stucray.limen.ui.pages.LandingPage;
import com.stucray.limen.ui.support.PlaywrightExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Playwright UI journey: a brand-new tenant owner signs up, lands on
 * the check-inbox page, the verification email arrives in Mailpit, the user
 * clicks the magic link, the OTT submit page POSTs the token, and the browser
 * lands on the tenant home with {@code email_verified=true} in the database.
 *
 * <p>Activates the {@code test} profile so the SMTP {@link com.stucray.limen.email.SmtpEmailSender}
 * fires (rather than the default LoggingEmailSender) and imports
 * {@link MailpitTestConfiguration} for the Mailpit Testcontainer + JavaMailSender.
 * Other UI tests stay on the logging driver to skip the Mailpit startup cost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, MailpitTestConfiguration.class})
@ActiveProfiles("test")
@ExtendWith(PlaywrightExtension.class)
@DisplayName("New tenant owner signs up, retrieves the verification email from Mailpit, clicks the magic link, and lands on home — fully verified")
class EmailVerificationJourneyUiIT {

    private static final Pattern MAGIC_LINK_PATTERN =
        Pattern.compile("(/t/[^/]+/login/ott\\?token=[A-Fa-f0-9-]+)");

    @LocalServerPort int port;
    @Autowired MailpitContainer mailpit;
    @Autowired JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("happy path: signup → check-inbox → click Mailpit magic link → tenant home; email_verified flips to true")
    void happyPath(Page page) throws Exception {
        String suffix = unique();
        String slug = "vrfy-" + suffix;
        String orgName = "Verify " + suffix;
        String email = "owner-" + suffix + "@example.test";
        String password = "secret123";

        CheckInboxPage checkInbox = new LandingPage(page, baseUrl())
            .visit()
            .clickSignUp()
            .fillForm(orgName, slug, email, password)
            .submit(slug)
            .assertHeading()
            .assertEmailShown(email);

        // Verification mail arrives in Mailpit; pull the magic link out of the body.
        String magicPath = waitForMagicLink(email);
        // Quick sanity: the link points at this tenant's prefix, not somebody else's.
        assertThat(magicPath).startsWith("/t/" + slug + "/login/ott?token=");

        // Clicking the link is a GET → renders the auto-submit OTT form.
        page.navigate(baseUrl() + magicPath);
        page.getByTestId("ott-submit").click();
        // Post-OTT dispatch lands on the tenant home (no saved request, no
        // pending password change, just the terminal tenantHome() intent).
        page.waitForURL(baseUrl() + "/t/" + slug + "/");

        Boolean verified = jdbcTemplate.queryForObject(
            "SELECT email_verified FROM users WHERE email = ?",
            Boolean.class, email);
        assertThat(verified).isTrue();
    }

    private String waitForMagicLink(String recipientEmail) throws Exception {
        RestClient restClient = RestClient.create(mailpit.httpApiBaseUrl());
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode messages = objectMapper.readTree(
                restClient.get().uri("/api/v1/messages?limit=50").retrieve().body(String.class));
            for (JsonNode m : messages.path("messages")) {
                JsonNode toArray = m.path("To");
                if (toArray.isArray() && toArray.size() > 0
                    && recipientEmail.equals(toArray.get(0).path("Address").asText())) {
                    String id = m.get("ID").asText();
                    JsonNode full = objectMapper.readTree(
                        restClient.get().uri("/api/v1/message/{id}", id).retrieve().body(String.class));
                    String text = full.path("Text").asText();
                    Matcher matcher = MAGIC_LINK_PATTERN.matcher(text);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for verification email to " + recipientEmail);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}

package com.stucray.limen.ui.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.email.MailpitContainer;
import com.stucray.limen.email.MailpitTestConfiguration;
import com.stucray.limen.ui.pages.ForgotPasswordPage;
import com.stucray.limen.ui.support.PlaywrightExtension;
import com.stucray.limen.ui.support.TestTenantFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Playwright UI journey for slice 5: an existing tenant admin clicks "Forgot
 * password?", asks for a reset, retrieves the magic link from Mailpit, sets a
 * new password, and verifies the new password works.
 *
 * <p>Activates the {@code test} profile so {@code limen.email.driver=smtp}
 * routes mail through {@link com.stucray.limen.email.SmtpEmailSender} into
 * Mailpit, mirroring {@link EmailVerificationJourneyUiIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, MailpitTestConfiguration.class})
@ActiveProfiles("test")
@ExtendWith(PlaywrightExtension.class)
@DisplayName("Forgot-password journey: existing user requests reset, retrieves Mailpit link, sets a new password, signs in with it")
class PasswordResetJourneyUiIT {

    private static final Pattern MAGIC_LINK_PATTERN =
        Pattern.compile("(/t/[^/]+/login/ott\\?token=[A-Fa-f0-9-]+)");

    @LocalServerPort int port;
    @Autowired MailpitContainer mailpit;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestTenantFactory tenantFactory;
    @Autowired PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("happy path: login → forgot-password → Mailpit reset link → change-password → re-login with new password")
    void happyPath(Page page) throws Exception {
        TestTenantFactory.SeededTenant tenant = tenantFactory.createTenant();
        String slug = tenant.slug();
        String email = tenant.adminEmail();
        String oldPassword = tenant.adminPassword();
        String newPassword = "fresh-secret-" + System.nanoTime();

        // Land on the login page and click the new "Forgot your password?" link.
        page.navigate(baseUrl() + "/t/" + slug + "/login");
        page.getByTestId("forgot-password-link").click();
        page.waitForURL(baseUrl() + "/t/" + slug + "/forgot-password");

        new ForgotPasswordPage(page, baseUrl(), slug)
            .assertHeading()
            .fillEmail(email)
            .submit();

        // Mailpit catches the reset email; pull the magic link.
        String magicPath = waitForResetLink(email);
        assertThat(magicPath).startsWith("/t/" + slug + "/login/ott?token=");

        // Click the link → ott-submit page → POST → routed through change-password.
        page.navigate(baseUrl() + magicPath);
        page.getByTestId("ott-submit").click();
        page.waitForURL(baseUrl() + "/t/" + slug + "/change-password");

        // Set the new password. Post-change terminal redirect /t/{slug}/ bounces
        // to the management home for owners (issue #283).
        page.getByLabel("New password").fill(newPassword);
        page.getByLabel("Confirm password").fill(newPassword);
        page.getByTestId("change-password-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + slug + "/");

        // Sanity: the stored hash matches the new password, not the old one.
        String storedHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?",
            String.class, email);
        assertThat(passwordEncoder.matches(newPassword, storedHash)).isTrue();
        assertThat(passwordEncoder.matches(oldPassword, storedHash)).isFalse();

        // Sign back in with the new password — log out first to clear the
        // post-OTT session before exercising form login. Owner login bounces
        // through /t/{slug}/ to /manage/t/{slug}/ (issue #283).
        page.context().clearCookies();
        page.navigate(baseUrl() + "/t/" + slug + "/login");
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password").fill(newPassword);
        page.getByTestId("login-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + slug + "/");

        // The completed reset emitted password_reset_completed; confirm the
        // audit row landed alongside the password_changed row. Async dispatch
        // via the Modulith publication registry means we poll until it lands.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_event "
                    + "WHERE event_type = 'password_reset_completed' "
                    + "AND tenant_id = ?",
                Long.class, tenant.tenantId());
            assertThat(count).isEqualTo(1L);
        });
    }

    private String waitForResetLink(String recipientEmail) throws Exception {
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
                    // The reset email body is distinctive; double-check we
                    // didn't pick up a verify-email message left over from a
                    // previous test in the same Mailpit container.
                    if (!text.contains("password-reset request")) {
                        continue;
                    }
                    Matcher matcher = MAGIC_LINK_PATTERN.matcher(text);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for password-reset email to " + recipientEmail);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}

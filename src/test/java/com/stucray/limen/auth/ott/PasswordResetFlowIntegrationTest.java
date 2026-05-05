package com.stucray.limen.auth.ott;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the OTT-driven password-reset flow at the HTTP layer:
 *
 * <ul>
 *   <li>The unauthenticated forgot-password POST returns the same redirect for
 *       known and unknown emails — the form is not a user-existence oracle.</li>
 *   <li>Magic-link consume (POST /t/&#123;slug&#125;/login/ott with a reset
 *       token) authenticates and routes to /change-password ahead of any saved
 *       /oauth2/authorize, via {@code passwordChangeAfterReset()}.</li>
 *   <li>Submitting the new password runs the existing change-password flow,
 *       clears the session marker, and emits {@code PasswordResetCompletedEvent}
 *       and {@code PasswordChangedEvent} (the latter as {@code SELF_SERVICE},
 *       because {@code mustChangePassword} is unset on a normal account).</li>
 *   <li>Single-use: a consumed reset OTT cannot be reused. Storage-side delete
 *       is shared with verify-email, but covered here against the reset-specific
 *       flow.</li>
 *   <li>Expiry: a token whose {@code expires_at} is in the past is rejected
 *       even before its single-use deletion fires.</li>
 *   <li>Audit: rows for {@code password_reset_ott_issued} (with
 *       {@code delivered=true} for known emails and {@code delivered=false} for
 *       unknown) and {@code password_reset_completed} appear after the matching
 *       events. The {@code password_changed} row from
 *       {@code TenantPasswordChangeFlow.changeAndRedirect} also lands.</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Password reset: forgot-password is no oracle; magic-link routes through change-password; reset completion + audit rows land")
class PasswordResetFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TenantAwareOneTimeTokenService tokenService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAudit() {
        jdbcTemplate.execute("DELETE FROM audit_event WHERE event_type IN "
            + "('password_reset_ott_issued', 'password_reset_completed', 'password_changed')");
    }

    @Nested
    @DisplayName("Forgot-password is not a user-existence oracle")
    class NoOracle {

        @Test
        @DisplayName("Known email returns the same redirect target as unknown email — same status, same Location")
        void identicalResponseForKnownAndUnknownEmail() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "noracle-" + suffix;
            String knownEmail = "known-" + suffix + "@example.test";
            String unknownEmail = "ghost-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "NoOracle " + suffix);
            userRepository.save(activeUser(tenant.id(), knownEmail));

            MvcResult known = mockMvc.perform(post("/t/" + slug + "/forgot-password")
                    .param("email", knownEmail).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            MvcResult unknown = mockMvc.perform(post("/t/" + slug + "/forgot-password")
                    .param("email", unknownEmail).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            assertThat(known.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus());
            // The Location query parameter encodes the email — so the two URLs
            // differ in that field, but both point at the same path with the
            // same flow=password-reset marker. Check the path + flow only.
            String knownLocation = known.getResponse().getHeader("Location");
            String unknownLocation = unknown.getResponse().getHeader("Location");
            assertThat(knownLocation).isNotNull();
            assertThat(unknownLocation).isNotNull();
            assertThat(stripEmail(knownLocation)).isEqualTo(stripEmail(unknownLocation));
            assertThat(knownLocation).contains("flow=password-reset");
            assertThat(unknownLocation).contains("flow=password-reset");
        }
    }

    @Nested
    @DisplayName("Magic-link consume routes through change-password")
    class ResetRoutes {

        @Test
        @DisplayName("After OTT consume the post-login dispatch redirects to /t/{slug}/change-password — passwordChangeAfterReset wins ahead of tenantHome")
        void ottConsumeRoutesToChangePassword() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "rst-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Reset " + suffix);
            userRepository.save(activeUser(tenant.id(), email));

            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.PASSWORD_RESET));

            MockHttpSession session = new MockHttpSession();
            MvcResult ottResult = mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue())
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            String location = ottResult.getResponse().getHeader("Location");
            assertThat(location).endsWith("/t/" + slug + "/change-password");
            // The session marker survived the redirect — the change-password
            // POST step will read it and emit the completed event.
            assertThat(session.getAttribute(PasswordResetSessionMarker.ATTRIBUTE_NAME))
                .isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("Submitting the new password clears the marker and emits password_reset_completed + password_changed audit rows")
        void submittingNewPasswordCompletesResetAndAuditsBothEvents() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "rst2-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            String newPassword = "new-secret-" + suffix;
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Reset2 " + suffix);
            User user = userRepository.save(activeUser(tenant.id(), email));

            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.PASSWORD_RESET));

            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue())
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

            mockMvc.perform(post("/t/" + slug + "/change-password")
                    .param("newPassword", newPassword)
                    .param("confirmPassword", newPassword)
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

            assertThat(session.getAttribute(PasswordResetSessionMarker.ATTRIBUTE_NAME)).isNull();

            Map<String, Object> resetCompleted = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, target_id FROM audit_event "
                    + "WHERE event_type = 'password_reset_completed' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(resetCompleted.get("actor_user_id")).isEqualTo(user.id());
            assertThat(resetCompleted.get("target_id")).isEqualTo(String.valueOf(user.id()));

            Map<String, Object> passwordChanged = jdbcTemplate.queryForMap(
                "SELECT details::text AS details FROM audit_event "
                    + "WHERE event_type = 'password_changed' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(passwordChanged.get("details").toString()).contains("self_service");
        }
    }

    @Nested
    @DisplayName("Reset OTT lifecycle")
    class TokenLifecycle {

        @Test
        @DisplayName("A consumed reset OTT cannot be reused — second consume returns 401-equivalent (no auth, no redirect to change-password)")
        void consumedResetTokenCannotBeReused() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "single-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Single " + suffix);
            userRepository.save(activeUser(tenant.id(), email));
            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.PASSWORD_RESET));

            // First consume — succeeds.
            mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue()).with(csrf()))
                .andExpect(status().is3xxRedirection());

            // Second consume — token row is gone, Spring's OTT filter falls
            // through to its failure handler (?error) rather than authenticating.
            // The exact redirect target is Spring Security's default OTT
            // failure URL ({@code /login?error}); what matters here is that the
            // user is not routed into the change-password form, since that
            // would mean a stale magic link still grants password-reset access.
            MvcResult second = mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
            String secondLocation = second.getResponse().getHeader("Location");
            assertThat(secondLocation).isNotNull();
            assertThat(secondLocation).doesNotContain("/change-password");
            assertThat(secondLocation).contains("error");
        }

        @Test
        @DisplayName("A reset OTT past expiry is rejected — TenantAwareOneTimeTokenService.consume returns null and the user is not authenticated")
        void expiredResetTokenIsRejected() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "expiry-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Expiry " + suffix);
            userRepository.save(activeUser(tenant.id(), email));
            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.PASSWORD_RESET));

            // Move the row's expires_at into the past so consume() picks it up
            // but rejects on the clock check. Mirrors how
            // TenantAwareOneTimeTokenServiceIntegrationTest exercises expiry.
            jdbcTemplate.update(
                "UPDATE one_time_tokens SET expires_at = ? WHERE token_value = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), issued.tokenValue());

            MvcResult result = mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
            String location = result.getResponse().getHeader("Location");
            assertThat(location).isNotNull();
            assertThat(location).doesNotContain("/change-password");
            assertThat(location).contains("error");
        }
    }

    @Nested
    @DisplayName("Audit rows for forgot-password attempts")
    class AuditRows {

        @Test
        @DisplayName("Forgot-password for a known email writes password_reset_ott_issued with delivered=true and a non-null actor")
        void knownEmailAuditsAsDelivered() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "auditok-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "AuditOK " + suffix);
            User user = userRepository.save(activeUser(tenant.id(), email));

            mockMvc.perform(post("/t/" + slug + "/forgot-password")
                    .param("email", email).with(csrf()))
                .andExpect(status().is3xxRedirection());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'password_reset_ott_issued' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(row.get("actor_user_id")).isEqualTo(user.id());
            assertThat(row.get("target_id")).isEqualTo(String.valueOf(user.id()));
            String details = row.get("details").toString().replace(" ", "");
            assertThat(details).contains("\"delivered\":true");
            assertThat(details).contains(email);
        }

        @Test
        @DisplayName("Forgot-password for an unknown email writes password_reset_ott_issued with delivered=false and null actor")
        void unknownEmailAuditsAsUndelivered() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "auditx-" + suffix;
            Tenant tenant = tenantProvisioningService.createTenant(slug, "AuditX " + suffix);

            mockMvc.perform(post("/t/" + slug + "/forgot-password")
                    .param("email", "ghost-" + suffix + "@example.test").with(csrf()))
                .andExpect(status().is3xxRedirection());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'password_reset_ott_issued' AND tenant_id = ? "
                    + "ORDER BY occurred_at DESC LIMIT 1",
                tenant.id());
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("target_id")).isNull();
            assertThat(row.get("details").toString().replace(" ", ""))
                .contains("\"delivered\":false");
        }
    }

    // --- helpers ---

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User activeUser(Long tenantId, String email) {
        // (id, tenantId, email, passwordHash, enabled, mustChangePassword,
        //  emailVerified, tenantOwner, createdAt). Verified=true here so the
        //  reset-flow tests focus on the reset path itself, not the verify-email
        //  side effect (covered separately in TenantOttAuthenticationProviderTest).
        return new User(
            null, tenantId, email,
            passwordEncoder.encode("old-secret"),
            true, false, true, false, LocalDateTime.now());
    }

    private static String stripEmail(String url) {
        return url.replaceAll("(\\?|&)email=[^&]*", "");
    }
}

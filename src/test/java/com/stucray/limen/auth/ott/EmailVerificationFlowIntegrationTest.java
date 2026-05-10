package com.stucray.limen.auth.ott;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembershipService;
import com.stucray.limen.memberships.ClientMembershipService;
import com.stucray.limen.memberships.ClientMembershipTestFixture;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the email-verification flow at the HTTP layer:
 *
 * <ul>
 *   <li>An unverified user attempting {@code /oauth2/authorize} is bounced to
 *       check-inbox by the {@code emailVerificationRequired()} post-login intent
 *       — even after a successful password login. The saved authorize request
 *       is held in the session, so the same user clicking the magic link
 *       afterwards completes the authorization without re-entering credentials.</li>
 *   <li>Clicking the magic link (POST /t/&#123;slug&#125;/login/ott with token)
 *       flips {@code email_verified=true} and lands the user on tenant home.</li>
 *   <li>Audit rows appear for every event published in the verification flow.</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Email verification: unverified users blocked from /oauth2/authorize; magic-link consume flips the bit and audit rows land")
class EmailVerificationFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TenantAwareOneTimeTokenService tokenService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAudit() {
        // Tests look at "the latest" event by tenant; clearing keeps assertions
        // unambiguous when prior tests also wrote rows.
        jdbcTemplate.execute("DELETE FROM audit_event WHERE event_type IN "
            + "('verification_ott_issued', 'email_verified')");
    }

    @Nested
    @DisplayName("Unverified user attempting /oauth2/authorize")
    class UnverifiedAuthorize {

        @Test
        @DisplayName("Successful password login redirects to /t/{slug}/check-inbox instead of completing the authorize flow")
        void unverifiedUserBouncedFromAuthorize() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "verify-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Verify " + suffix);
            // Insert an unverified user directly so the integration test does not
            // depend on the precise mail behaviour of SignupService.
            User user = userRepository.save(new User(
                null, tenant.id(), email,
                passwordEncoder.encode("password"),
                true, false, true, false, LocalDateTime.now()));
            TenantClient client = createPkceClient(tenant, "unverified-authz");
            ClientMembershipTestFixture.grant(
                applicationMembershipService, clientMembershipService,
                client.applicationId(), tenant.id(), user.id(), user.id(),
                client.registeredClientId(), Set.of()
            );

            MockHttpSession session = new MockHttpSession();
            String authzUri = authorizeUri(slug, client);
            mockMvc.perform(get(authzUri).session(session))
                .andExpect(status().is3xxRedirection());

            MvcResult loginResult = mockMvc.perform(post("/t/" + slug + "/login")
                    .param("email", email).param("password", "password")
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            String location = loginResult.getResponse().getHeader("Location");
            assertThat(location).endsWith("/t/" + slug + "/check-inbox");
            assertThat(location).doesNotContain("/oauth2/authorize");
        }

        @Test
        @DisplayName("After OTT consume the same session resumes the saved authorize request — the magic link both verifies the user and unblocks the original flow")
        void postOttConsumeResumesAuthorize() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "verify-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Verify " + suffix);
            User user = userRepository.save(new User(
                null, tenant.id(), email,
                passwordEncoder.encode("password"),
                true, false, true, false, LocalDateTime.now()));
            TenantClient client = createPkceClient(tenant, "ott-resumes-authz");
            ClientMembershipTestFixture.grant(
                applicationMembershipService, clientMembershipService,
                client.applicationId(), tenant.id(), user.id(), user.id(),
                client.registeredClientId(), Set.of()
            );

            // Issue the OTT directly under the tenant scope (mirrors what
            // OttDispatcher.issue(VERIFY_EMAIL, tenant, owner) does after signup).
            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.VERIFY_EMAIL));

            MockHttpSession session = new MockHttpSession();
            String authzUri = authorizeUri(slug, client);
            mockMvc.perform(get(authzUri).session(session))
                .andExpect(status().is3xxRedirection());

            // POST the OTT — Spring's filter runs consume + auth + success-handler.
            MvcResult ottResult = mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue())
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
            String afterOtt = ottResult.getResponse().getHeader("Location");
            // The PostLoginIntent chain runs emailVerificationRequired() first
            // (now passes — flag was just flipped), then resumeOAuth2Authorize()
            // (saved request is the /oauth2/authorize call).
            assertThat(afterOtt).contains("/t/" + slug + "/oauth2/authorize");

            Boolean verified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE id = ?",
                Boolean.class, user.id());
            assertThat(verified).isTrue();
        }
    }

    @Nested
    @DisplayName("Audit rows for verification events")
    class AuditRows {

        @Test
        @DisplayName("Signup publishes VerificationOttIssuedEvent → verification_ott_issued row with email in details")
        void signupEmitsVerificationOttIssuedAuditRow() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "audit-" + suffix;
            String email = "owner-" + suffix + "@example.test";

            mockMvc.perform(post("/signup")
                    .param("organizationName", "Audit " + suffix)
                    .param("slug", slug)
                    .param("email", email)
                    .param("password", "password")
                    .with(csrf()))
                .andExpect(status().is3xxRedirection());

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT event_type, target_type, details::text AS details FROM audit_event "
                        + "WHERE event_type = 'verification_ott_issued' "
                        + "AND tenant_id = (SELECT id FROM tenants WHERE slug = ?) "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                    slug);
                assertThat(rows).isNotEmpty();
                Map<String, Object> row = rows.get(0);
                assertThat(row.get("target_type")).isEqualTo("user");
                assertThat(row.get("details").toString()).contains(email);
            });
        }

        @Test
        @DisplayName("Magic-link consume publishes EmailVerifiedEvent → email_verified row with email in details")
        void ottConsumeEmitsEmailVerifiedAuditRow() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "audit2-" + suffix;
            String email = "owner-" + suffix + "@example.test";
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Audit2 " + suffix);
            User user = userRepository.save(new User(
                null, tenant.id(), email,
                passwordEncoder.encode("password"),
                true, false, true, false, LocalDateTime.now()));

            TenantOneTimeToken issued = TenantScope.call(tenant.slug(), tenant.id(), () ->
                tokenService.generateForIntent(email, OttIntent.VERIFY_EMAIL));
            mockMvc.perform(post("/t/" + slug + "/login/ott")
                    .param("token", issued.tokenValue())
                    .with(csrf()))
                .andExpect(status().is3xxRedirection());

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT actor_user_id, target_id, details::text AS details FROM audit_event "
                        + "WHERE event_type = 'email_verified' AND tenant_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                    tenant.id());
                assertThat(rows).isNotEmpty();
                Map<String, Object> row = rows.get(0);
                assertThat(row.get("actor_user_id")).isEqualTo(user.id());
                assertThat(row.get("target_id")).isEqualTo(String.valueOf(user.id()));
                assertThat(row.get("details").toString()).contains(email);
            });
        }

        @Test
        @DisplayName("Resend for an unknown email publishes verification_ott_issued with delivered=false and null user (existence-oracle defence)")
        void resendUnknownEmailEmitsAuditRowWithDeliveredFalse() throws Exception {
            String suffix = uniqueSuffix();
            String slug = "audit3-" + suffix;
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Audit3 " + suffix);

            mockMvc.perform(post("/t/" + slug + "/resend-verification")
                    .param("email", "ghost-" + suffix + "@example.test")
                    .with(csrf()))
                .andExpect(status().is3xxRedirection());

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT actor_user_id, target_id, details::text AS details FROM audit_event "
                        + "WHERE event_type = 'verification_ott_issued' AND tenant_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                    tenant.id());
                assertThat(rows).isNotEmpty();
                Map<String, Object> row = rows.get(0);
                assertThat(row.get("actor_user_id")).isNull();
                assertThat(row.get("target_id")).isNull();
                assertThat(row.get("details").toString().replace(" ", ""))
                    .contains("\"delivered\":false");
            });
        }
    }

    // --- helpers ---

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private TenantClient createPkceClient(Tenant tenant, String name) {
        Application app = applicationRepository.save(new Application(
            null, tenant.id(), name + "-app", null, LocalDateTime.now()));
        String internalId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        return tenantClientRepository.save(new TenantClient(
            null, internalId, app.id(), tenant.id(), name, false));
    }

    private String authorizeUri(String slug, TenantClient client) throws Exception {
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, client.registeredClientId());
        return UriComponentsBuilder.fromPath("/t/" + slug + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "s1")
            .queryParam("code_challenge", pkceChallenge())
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
    }

    private static String pkceChallenge() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}

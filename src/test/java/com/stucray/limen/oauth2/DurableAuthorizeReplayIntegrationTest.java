package com.stucray.limen.oauth2;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 2 of PRD #328 (issue #327): a pending {@code /oauth2/authorize} request
 * is stashed durably when an unauthenticated authorize bounces to the login
 * page, and replayed after the HTTP session (and its in-session SavedRequest)
 * has been evicted. The complementary session-path resume is pinned by
 * {@code OAuth2ForcedPasswordChangeIntegrationTest} /
 * {@code OAuth2ConsentResumeIntegrationTest}; this class pins the durable
 * fallback and its graceful both-expired degradation.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Durable /oauth2/authorize replay survives HTTP session eviction (issue #327)")
class DurableAuthorizeReplayIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;
    Application app;
    String oauthClientId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM pending_authorize");
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM persistent_logins");

        tenant = tenantRepository.save(new Tenant(
            null, "alpha-corp", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        app = applicationRepository.save(new Application(
            null, tenant.id(), "Alpha App", "Test app", LocalDateTime.now()));

        String registeredClientId = UUID.randomUUID().toString();
        oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
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
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, app.id(), tenant.id(), "Test Client", false));
    }

    @Test
    @DisplayName("Unauthenticated /oauth2/authorize bounces to the login page with an opaque ?ref= and stashes the request")
    void authorizeBounceCarriesRefAndStashes() throws Exception {
        String location = authorizeBounce().getResponse().getHeader("Location");

        assertThat(location).startsWith("/t/alpha-corp/login?ref=");
        assertThat(refFrom(location)).isNotBlank();
        Integer stashed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pending_authorize WHERE tenant_slug = ?", Integer.class, "alpha-corp");
        assertThat(stashed).isEqualTo(1);
    }

    @Test
    @DisplayName("After session eviction, logging in with the carried ref replays /t/{slug}/oauth2/authorize")
    void sessionEvictedLoginReplaysAuthorize() throws Exception {
        seedUser("alice@example.test", "password", false);
        String ref = refFrom(authorizeBounce().getResponse().getHeader("Location"));

        // Fresh session — the one that held the in-session SavedRequest is gone.
        MvcResult login = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "password")
                .param("ref", ref)
                .session(new MockHttpSession()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = login.getResponse().getHeader("Location");
        assertThat(location).startsWith("/t/alpha-corp/oauth2/authorize?");
        assertThat(location).contains("client_id=" + oauthClientId);
        assertThat(location).contains("code_challenge=");
    }

    @Test
    @DisplayName("When both the session and the durable record are gone, login lands on the neutral end-user home, not /manage")
    void bothExpiredFallsThroughToNeutralHome() throws Exception {
        seedUser("alice@example.test", "password", false);

        MvcResult login = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "password")
                .param("ref", "no-such-reference")
                .session(new MockHttpSession()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(login.getResponse().getHeader("Location")).isEqualTo("/t/alpha-corp/");
    }

    @Test
    @DisplayName("A must-change-password user with a ref is routed to change-password FIRST — resume yields to the higher-priority intent")
    void passwordChangeIntentFiresAheadOfDurableResume() throws Exception {
        seedUser("bob@example.test", "password", true);
        String ref = refFrom(authorizeBounce().getResponse().getHeader("Location"));

        MvcResult login = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "bob@example.test").param("password", "password")
                .param("ref", ref)
                .session(new MockHttpSession()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(login.getResponse().getHeader("Location")).isEqualTo("/t/alpha-corp/change-password");
    }

    private MvcResult authorizeBounce() throws Exception {
        String authzUri = UriComponentsBuilder.fromPath("/t/alpha-corp/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", newChallenge())
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
        return mockMvc.perform(get(authzUri).session(new MockHttpSession()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    }

    private void seedUser(String email, String password, boolean mustChangePassword) {
        userRepository.save(new User(
            null, tenant.id(), email, passwordEncoder.encode(password),
            true, mustChangePassword, false, true, LocalDateTime.now()));
    }

    private static String newChallenge() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String refFrom(String location) {
        int i = location.indexOf("ref=");
        return i < 0 ? "" : location.substring(i + "ref=".length());
    }
}

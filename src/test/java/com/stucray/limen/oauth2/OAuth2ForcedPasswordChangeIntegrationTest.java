package com.stucray.limen.oauth2;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembershipService;
import com.stucray.limen.memberships.ClientMembershipService;
import com.stucray.limen.memberships.ClientMembershipTestFixture;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OAuth2 forced-password-change: mustChangePassword=true intercepts the authorize flow until the password is reset")
class OAuth2ForcedPasswordChangeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;
    Application app;
    String oauthClientId;
    String registeredClientId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenant = tenantRepository.save(new Tenant(
            null, "alpha-corp", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        app = applicationRepository.save(new Application(
            null, tenant.id(), "Alpha App", "Test app", LocalDateTime.now()));

        registeredClientId = UUID.randomUUID().toString();
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
    @DisplayName("During an /oauth2/authorize flow, a user with mustChangePassword=true is redirected to the tenant change-password page after login")
    void userWithMustChangePasswordRedirectedToChangePasswordPage() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(loginResult.getResponse().getHeader("Location"))
            .isEqualTo("/t/alpha-corp/change-password");
    }

    @Test
    @DisplayName("Successful change-password clears mustChangePassword and resumes the saved /oauth2/authorize request, ultimately yielding an authorization code")
    void changePasswordResumesOAuth2FlowAndClearsFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), original.id(), original.id(),
            registeredClientId, Set.of()
        );

        MockHttpSession session = startAuthorize(newPkce().challenge());

        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult changeResult = mockMvc.perform(post("/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "newpass123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(changeResult.getResponse().getHeader("Location"))
            .contains("/t/alpha-corp/oauth2/authorize");
        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isFalse();

        MvcResult codeResult = mockMvc.perform(get(changeResult.getResponse().getHeader("Location")).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String code = UriComponentsBuilder.fromUriString(codeResult.getResponse().getHeader("Location"))
            .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();
    }

    @Test
    @DisplayName("Mismatched new/confirm re-renders the form and leaves mustChangePassword=true so the user remains gated")
    void mismatchedPasswordsRerendersFormAndDoesNotClearFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "different1")
                .session(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("A user without mustChangePassword goes straight from login back to /oauth2/authorize without the change-password detour")
    void userWithoutMustChangePasswordCompletesNormally() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "bob@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "bob@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(loginResult.getResponse().getHeader("Location"))
            .contains("/t/alpha-corp/oauth2/authorize");
    }

    @Test
    @DisplayName("Blank/whitespace new password re-renders the form and leaves mustChangePassword=true")
    void blankNewPasswordRerendersFormAndDoesNotClearFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/alpha-corp/change-password")
                .param("newPassword", "   ").param("confirmPassword", "   ")
                .session(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Direct change-password (no SavedRequest in cache) succeeds and falls back to the tenant home /t/{slug}/")
    void changePasswordWithoutSavedRequestRedirectsToTenantHome() throws Exception {
        // Direct visit to the change-password page (no prior /oauth2/authorize → no
        // SavedRequest in the cache); after success the controller should fall back
        // to redirect:/t/{slug}/.
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(post("/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "newpass123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/t/alpha-corp/");
        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("GET /t/{slug}/change-password renders the change-password view for an authenticated mustChangePassword user")
    void changePasswordFormGetRendersChangePasswordView() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/t/alpha-corp/change-password").session(session))
            .andExpect(status().isOk())
            // Pin view selection: the OAuth2 surface uses the bare "change-password"
            // template, not the management-surface "manage/users/change-password" one.
            .andExpect(view().name("change-password"));
    }

    private MockHttpSession startAuthorize(String codeChallenge) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String authzUri = UriComponentsBuilder.fromPath("/t/alpha-corp/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());
        return session;
    }

    private Pkce newPkce() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return new Pkce(verifier, challenge);
    }

    private record Pkce(String verifier, String challenge) {}
}

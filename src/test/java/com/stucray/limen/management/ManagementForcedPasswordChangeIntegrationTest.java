package com.stucray.limen.management;

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
 * Slice 2 of #67. Pins the two latent-bug fixes for the management surface
 * (saved /oauth2/authorize resume on login; password-change-required redirect
 * on login) and the new /manage/t/{slug}/change-password endpoint behaviour.
 * Mirrors {@code OAuth2ForcedPasswordChangeIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Management surface: forced password change + saved /authorize resume")
class ManagementForcedPasswordChangeIntegrationTest {

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
    @DisplayName("Login with must_change_password=true redirects to /manage/t/{slug}/change-password")
    void managementLoginRedirectsToChangePasswordWhenMustChangeFlagSet() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MvcResult loginResult = mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(loginResult.getResponse().getHeader("Location"))
            .isEqualTo("/manage/t/alpha-corp/change-password");
    }

    @Test
    @DisplayName("Login with a saved /oauth2/authorize redirects to the tenant-prefixed authorize URL, not the management home")
    void managementLoginResumesSavedOAuth2AuthorizeRequest() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "owner@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        MvcResult loginResult = mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "owner@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        // The saved /oauth2/authorize request resumes (tenant-prefixed) instead
        // of the management home page being rendered.
        assertThat(loginResult.getResponse().getHeader("Location"))
            .contains("/t/alpha-corp/oauth2/authorize");
    }

    @Test
    @DisplayName("Successful password change resumes the saved /oauth2/authorize flow and clears must_change_password")
    void changePasswordResumesOAuth2FlowAndClearsFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = startAuthorize(newPkce().challenge());

        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult changeResult = mockMvc.perform(post("/manage/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "newpass123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(changeResult.getResponse().getHeader("Location"))
            .contains("/t/alpha-corp/oauth2/authorize");
        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("Mismatched newPassword/confirmPassword re-renders the form; flag stays set")
    void mismatchedPasswordsRerendersFormAndDoesNotClearFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/manage/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "different1")
                .session(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Blank/whitespace newPassword re-renders the form; flag stays set")
    void blankNewPasswordRerendersFormAndDoesNotClearFlag() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/manage/t/alpha-corp/change-password")
                .param("newPassword", "   ").param("confirmPassword", "   ")
                .session(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Successful password change with no saved request redirects to /manage/t/{slug}/")
    void changePasswordWithoutSavedRequestRedirectsToManagementHome() throws Exception {
        User original = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(post("/manage/t/alpha-corp/change-password")
                .param("newPassword", "newpass123")
                .param("confirmPassword", "newpass123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/manage/t/alpha-corp/");
        assertThat(userRepository.findById(original.id()).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("GET /manage/t/{slug}/change-password renders the change-password form")
    void changePasswordFormGetRendersChangePasswordView() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("temp"),
            true, true, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "temp")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/manage/t/alpha-corp/change-password").session(session))
            .andExpect(status().isOk());
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

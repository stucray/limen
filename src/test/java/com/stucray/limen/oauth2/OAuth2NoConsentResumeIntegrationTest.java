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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for #285: when an unauthenticated user hits
 * {@code /t/{slug}/oauth2/authorize} on a client with
 * {@code requireAuthorizationConsent=false} (the default after #277), the
 * post-login redirect must resume the saved authorize request — not fall
 * through to the tenant home and bounce to {@code /manage/t/{slug}/}.
 *
 * <p>Sibling to {@link OAuth2ConsentResumeIntegrationTest} which exercises the
 * {@code requireAuthorizationConsent=true} branch. Both depend on the same
 * post-login resume; splitting the two pins the resume independent of which
 * post-authorize path SAS takes (consent vs. direct code issue).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/oauth2/authorize post-login resume works when client has requireAuthorizationConsent=false (#285)")
class OAuth2NoConsentResumeIntegrationTest {

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
    User alice;

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
            null, "overround", "Overround", TenantStatus.ACTIVE, LocalDateTime.now()));
        app = applicationRepository.save(new Application(
            null, tenant.id(), "BFF", "BFF gateway app", LocalDateTime.now()));
        alice = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()));

        registeredClientId = UUID.randomUUID().toString();
        oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8091/login/oauth2/code/bff-client")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, app.id(), tenant.id(), "bff-client", false));

        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), alice.id(), alice.id(),
            registeredClientId, java.util.Set.of()
        );
    }

    @Test
    @DisplayName("Post-login redirect Location is the resumed /t/{slug}/oauth2/authorize URL, not /manage/t/{slug}/")
    void postLoginResumesAuthorizeWhenConsentNotRequired() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String authzUri = buildAuthorizeUri();

        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());

        MvcResult loginResult = mockMvc.perform(post("/t/overround/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = loginResult.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        assertThat(location)
            .as("post-login Location should resume /oauth2/authorize, not bounce to /manage/")
            .contains("/t/overround/oauth2/authorize");
        assertThat(location).doesNotContain("/manage/");
    }

    @Nested
    @DisplayName("Saved /oauth2/authorize request survives interleaving requests that hit other security chains (#285)")
    class SavedRequestSurvivesInterleavedRequests {

        @Test
        @DisplayName("Chrome DevTools probe to /.well-known/appspecific/com.chrome.devtools.json between /oauth2/authorize and /login does not break post-login resume")
        void devtoolsProbeDoesNotOverwriteSavedAuthorizeRequest() throws Exception {
            assertResumeSurvivesInterleavedRequest(
                "/.well-known/appspecific/com.chrome.devtools.json",
                ".well-known"
            );
        }

        @Test
        @DisplayName("Any deny-all GET against the catch-all chain between /oauth2/authorize and /login does not break post-login resume — the SAS SavedRequest is isolated from other chains, not just from the DevTools probe")
        void arbitraryDeniedRequestDoesNotOverwriteSavedAuthorizeRequest() throws Exception {
            // Generic deny-all URL — proves the fix is structural (dedicated session
            // attribute on the SAS chain) rather than a workaround for one specific
            // trigger. Could be a browser extension probe, a stale GET from another
            // tab, a Spring /error internal forward, an iOS apple-touch-icon, etc.
            assertResumeSurvivesInterleavedRequest("/some-arbitrary-denied-path", "arbitrary");
        }

        private void assertResumeSurvivesInterleavedRequest(
            String interleavedUri, String unexpectedLocationFragment
        ) throws Exception {
            MockHttpSession session = new MockHttpSession();

            mockMvc.perform(get(buildAuthorizeUri()).session(session))
                .andExpect(status().is3xxRedirection());

            mockMvc.perform(get(interleavedUri)
                    .session(session)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
                .andExpect(status().isForbidden());

            MvcResult loginResult = mockMvc.perform(post("/t/overround/login")
                    .param("email", "alice@example.test").param("password", "password")
                    .session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            String location = loginResult.getResponse().getHeader("Location");
            assertThat(location).isNotNull();
            assertThat(location)
                .as("post-login Location must resume /oauth2/authorize even after %s", interleavedUri)
                .contains("/t/overround/oauth2/authorize");
            assertThat(location).doesNotContain("/manage/");
            assertThat(location).doesNotContain(unexpectedLocationFragment);
        }
    }

    private String buildAuthorizeUri() {
        return UriComponentsBuilder.fromPath("/t/overround/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost:8091/login/oauth2/code/bff-client")
            .queryParam("scope", OidcScopes.OPENID + " " + OidcScopes.PROFILE)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", "fake-challenge")
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
    }
}

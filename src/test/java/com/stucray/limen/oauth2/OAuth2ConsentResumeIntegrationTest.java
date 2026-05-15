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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for #276: when an unauthenticated user hits
 * {@code /oauth2/authorize} on a client with {@code requireAuthorizationConsent=true},
 * Spring Security's {@code HttpSessionRequestCache} stamps the resumed URL with
 * its {@code continue} query-optimisation marker (spring-security#13438). SAS's
 * authorize validator then rejects the extra parameter, surfaced as
 * {@code access_denied}. {@link com.stucray.limen.auth.login.PostLoginIntents}
 * strips the marker at the resume boundary; this test pins that behaviour
 * end-to-end through MockMvc.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/oauth2/authorize post-login resume strips Spring Security's `continue` marker (#276)")
class OAuth2ConsentResumeIntegrationTest {

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
            null, "consent-corp", "Consent Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        app = applicationRepository.save(new Application(
            null, tenant.id(), "Consent App", "Consent flow test app", LocalDateTime.now()));
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
            .redirectUri("http://localhost/callback")
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, app.id(), tenant.id(), "Consent Client", false));

        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), alice.id(), alice.id(),
            registeredClientId, java.util.Set.of()
        );
    }

    @Test
    @DisplayName("Post-login redirect Location does not carry the `continue` marker on the resumed /oauth2/authorize URL")
    void postLoginRedirectStripsContinueMarker() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String authzUri = UriComponentsBuilder.fromPath("/t/consent-corp/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", "fake-challenge")
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());

        MvcResult loginResult = mockMvc.perform(post("/t/consent-corp/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = loginResult.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        assertThat(location).contains("/t/consent-corp/oauth2/authorize");
        assertThat(location).doesNotContain("continue");
    }
}

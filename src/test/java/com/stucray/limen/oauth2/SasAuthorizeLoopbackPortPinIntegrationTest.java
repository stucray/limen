package com.stucray.limen.oauth2;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembership;
import com.stucray.limen.memberships.ApplicationMembershipRepository;
import com.stucray.limen.memberships.ClientMembership;
import com.stucray.limen.memberships.ClientMembershipRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression sensor for Spring Authorization Server's loopback port-wildcarding
 * branch on {@code /oauth2/authorize} (RFC 8252 §7.3). The behaviour under test
 * is owned by SAS — not by Limen — and has shipped since SAS 0.1.1 (March 2021,
 * closing SAS issue #243) via {@code OAuth2AuthorizeCodeRequestAuthenticationValidator}.
 *
 * <p>This test exists so a future SAS upgrade that tightens, broadens, or
 * removes the loopback branch (e.g. extending it to {@code localhost} per a
 * future RFC update, or reverting it under a security advisory) fails at CI
 * time instead of silently regressing Limen's local-dev DX. PRD #316; slice
 * issue #317.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SAS pin: /oauth2/authorize wildcards ports for HTTP loopback redirect URIs (RFC 8252 §7.3)")
class SasAuthorizeLoopbackPortPinIntegrationTest {

    private static final String REGISTERED_REDIRECT = "http://127.0.0.1:9000/callback";
    private static final String REQUESTED_REDIRECT = "http://127.0.0.1:9001/callback";

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ApplicationMembershipRepository applicationMembershipRepository;
    @Autowired ClientMembershipRepository clientMembershipRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private Tenant tenant;
    private Application application;
    private User user;
    private User admin;

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

        tenant = tenantProvisioningService.createTenant("pin-tenant", "Pin Tenant");
        application = applicationRepository.save(new Application(
            null, tenant.id(), "Pin App", null, LocalDateTime.now()
        ));
        user = userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));
        admin = userRepository.save(new User(
            null, tenant.id(), "admin@example.test",
            passwordEncoder.encode("password"),
            true, false, true, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("requested 127.0.0.1:<portB>/callback against registered 127.0.0.1:<portA>/callback yields a 302 to the requested URI carrying an authorization code")
    void loopbackHostMatchesAnyPort() throws Exception {
        String internalId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REGISTERED_REDIRECT)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        TenantClient client = tenantClientRepository.save(new TenantClient(
            null, internalId, application.id(), tenant.id(), "pin-client", false
        ));

        // Get past the Client Membership gate so the redirect_uri match is the
        // load-bearing question the test is pinning.
        ApplicationMembership am = applicationMembershipRepository.save(new ApplicationMembership(
            null, user.id(), application.id(), LocalDateTime.now(), admin.id(), Set.of()
        ));
        clientMembershipRepository.save(new ClientMembership(
            null, user.id(), client.id(), am.id(),
            LocalDateTime.now(), admin.id(), Set.of()
        ));

        Pkce pkce = pkce();
        String authzUri = UriComponentsBuilder.fromPath("/t/" + tenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", REQUESTED_REDIRECT)
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "pin-s1")
            .queryParam("code_challenge", pkce.challenge())
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + tenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location)
            .as("SAS must redirect to the *requested* loopback URI (port from %s), proving its loopback port-wildcarding (RFC 8252 §7.3) still applies; got: %s",
                REQUESTED_REDIRECT, location)
            .startsWith(REQUESTED_REDIRECT + "?");
        Map<String, String> params = UriComponentsBuilder.fromUriString(location)
            .build().getQueryParams().toSingleValueMap();
        assertThat(params)
            .as("Authorization-code response on the requested loopback URI must carry a code and no error; got: %s", location)
            .containsKey("code")
            .doesNotContainKey("error");
    }

    private static Pkce pkce() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return new Pkce(verifier, challenge);
    }

    private record Pkce(String verifier, String challenge) {}
}

package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.management.clients.TenantClient;
import com.stucray.limen.management.clients.TenantClientRepository;
import com.stucray.limen.management.memberships.ApplicationMembershipService;
import com.stucray.limen.management.memberships.ClientMembershipService;
import com.stucray.limen.management.memberships.ClientMembershipTestFixture;
import com.stucray.limen.management.roles.Role;
import com.stucray.limen.management.roles.RoleRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: a User with an Application Membership and a Client Membership
 * carrying Roles completes the authorization code + PKCE flow, and the
 * resulting JWT carries the Roles in the {@code roles} claim. The
 * Membership-without-Roles branch still issues a token with empty
 * {@code roles}; the no-Membership branch is rejected by the gate (slice 5 /
 * #44) and is covered in {@link OAuth2AuthorizeMembershipGateIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2JwtRolesClaimIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    Tenant tenant;
    Application app;
    User alice;
    User admin;

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

        tenant = tenantProvisioningService.createTenant("acme", "Acme");
        app = applicationRepository.save(new Application(
            null, tenant.id(), "Acme Web", "End-to-end test app", LocalDateTime.now()
        ));
        alice = userRepository.save(new User(
            null, tenant.id(), "alice",
            passwordEncoder.encode("password"),
            true, false, false, LocalDateTime.now()
        ));
        admin = userRepository.save(new User(
            null, tenant.id(), "admin",
            passwordEncoder.encode("password"),
            true, false, true, LocalDateTime.now()
        ));
    }

    @Test
    void jwtCarriesClientMembershipRolesForEndUser() throws Exception {
        // 1. Define Roles and a public PKCE Client.
        Role viewer = roleRepository.save(new Role(null, app.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, app.id(), "editor", null, LocalDateTime.now()));
        TenantClient client = createPkceClient("acme-spa");

        // 2. Grant Application + Client Membership and assign the Roles.
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), alice.id(), admin.id(),
            client.registeredClientId(), Set.of(viewer.id(), editor.id())
        );

        // 3. Run the authorization code + PKCE flow as alice.
        Map<String, Object> claims = runAuthorizationCodeFlow(client, "alice");

        // 4. Verify JWT claims.
        assertThat(claims.get("tenant")).isEqualTo("acme");
        assertThat(claims.get("roles")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        assertThat(roles).containsExactly("editor", "viewer");
        assertThat(claims.get("iss")).asString().isEqualTo("http://localhost/t/acme");
    }

    @Test
    void jwtCarriesEmptyRolesWhenMembershipHasNoRolesAssigned() throws Exception {
        // Membership presence (not Role count) is the gate — Membership without
        // Roles passes the gate and yields a JWT with roles: [].
        TenantClient client = createPkceClient("acme-spa-no-roles");
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), alice.id(), admin.id(),
            client.registeredClientId(), Set.of()
        );

        Map<String, Object> claims = runAuthorizationCodeFlow(client, "alice");

        assertThat(claims.get("tenant")).isEqualTo("acme");
        assertThat((List<?>) claims.get("roles")).isEmpty();
    }

    private TenantClient createPkceClient(String name) {
        String internalId = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(clientId)
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
            null, internalId, app.id(), tenant.id(), name, false
        ));
    }

    private Map<String, Object> runAuthorizationCodeFlow(TenantClient client, String username) throws Exception {
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, client.registeredClientId()
        );

        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        String authzUri = UriComponentsBuilder.fromPath("/t/acme/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "s1")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        MockHttpSession session = new MockHttpSession();
        MvcResult authzResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        assertThat(authzResult.getResponse().getHeader("Location")).contains("/t/acme/login");

        mockMvc.perform(post("/t/acme/login")
                .param("username", username)
                .param("password", "password")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult codeResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String code = UriComponentsBuilder.fromUriString(codeResult.getResponse().getHeader("Location"))
            .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        String tokenJson = mockMvc.perform(post("/t/acme/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .param("code_verifier", codeVerifier)
                .param("client_id", oauthClientId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        return SignedJWT.parse(accessToken).getJWTClaimsSet().getClaims();
    }
}

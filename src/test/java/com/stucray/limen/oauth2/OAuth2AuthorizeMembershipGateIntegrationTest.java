package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembershipService;
import com.stucray.limen.memberships.ClientMembershipService;
import com.stucray.limen.memberships.ClientMembershipTestFixture;
import com.stucray.limen.roles.Role;
import com.stucray.limen.roles.RoleRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /oauth2/authorize Client Membership gate (slice 5 / #44). End-User Login
 * succeeds in every test — the gate sits one step downstream and rejects the
 * authorization code request with {@code access_denied} when no
 * {@code client_membership} row exists for {@code (user, registeredClient,
 * tenant)}. Membership presence (not Role count) is the gate; cross-tenant
 * isolation is checked by attempting a flow with a User authenticated against
 * a different Tenant's URL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/oauth2/authorize client-membership gate: deny unless (user, registered_client, tenant) has a membership row")
class OAuth2AuthorizeMembershipGateIntegrationTest {

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

    Tenant alphaTenant;
    Tenant betaTenant;
    Application alphaApp;
    Application betaApp;
    User aliceAlpha;
    User adminAlpha;
    User bobBeta;
    User adminBeta;

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

        alphaTenant = tenantProvisioningService.createTenant("gate-alpha", "Gate Alpha");
        betaTenant = tenantProvisioningService.createTenant("gate-beta", "Gate Beta");
        alphaApp = applicationRepository.save(new Application(
            null, alphaTenant.id(), "Alpha App", null, LocalDateTime.now()
        ));
        betaApp = applicationRepository.save(new Application(
            null, betaTenant.id(), "Beta App", null, LocalDateTime.now()
        ));
        aliceAlpha = userRepository.save(new User(
            null, alphaTenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));
        adminAlpha = userRepository.save(new User(
            null, alphaTenant.id(), "alpha-admin@example.test",
            passwordEncoder.encode("password"),
            true, false, true, true, LocalDateTime.now()
        ));
        bobBeta = userRepository.save(new User(
            null, betaTenant.id(), "bob@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));
        adminBeta = userRepository.save(new User(
            null, betaTenant.id(), "beta-admin@example.test",
            passwordEncoder.encode("password"),
            true, false, true, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("Authenticated user with no client_membership is rejected with access_denied (state preserved, no code issued)")
    void userWithoutClientMembershipIsDeniedAtAuthorize() throws Exception {
        TenantClient client = createPkceClient(alphaApp, alphaTenant, "no-membership-client");

        // alice authenticates successfully; the gate rejects the authorization
        // request with access_denied (302 to redirect_uri).
        String location = runFlowToAuthorizeRedirect(alphaTenant.slug(), client, "alice@example.test", "password");

        Map<String, String> params = queryParams(location);
        assertThat(params.get("error")).isEqualTo("access_denied");
        assertThat(params.get("state")).isEqualTo("s1");
        assertThat(params).doesNotContainKey("code");
    }

    @Test
    @DisplayName("Membership presence is the gate, not role count — a user with zero roles still gets a token (with an empty roles claim)")
    void userWithMembershipAndZeroRolesPassesGate() throws Exception {
        TenantClient client = createPkceClient(alphaApp, alphaTenant, "zero-roles-client");
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            alphaApp.id(), alphaTenant.id(), aliceAlpha.id(), adminAlpha.id(),
            client.registeredClientId(), Set.of()
        );

        Map<String, Object> claims = runFlowAndExtractJwtClaims(
            alphaTenant.slug(), client, "alice@example.test", "password"
        );

        assertThat(claims.get("tenant")).isEqualTo("gate-alpha");
        assertThat((List<?>) claims.get("roles")).isEmpty();
    }

    @Test
    @DisplayName("A user with membership + roles gets an access token whose roles claim lists their granted role names")
    void userWithMembershipAndRolesGetsJwtWithRoles() throws Exception {
        TenantClient client = createPkceClient(alphaApp, alphaTenant, "with-roles-client");
        Role viewer = roleRepository.save(new Role(null, alphaApp.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, alphaApp.id(), "editor", null, LocalDateTime.now()));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            alphaApp.id(), alphaTenant.id(), aliceAlpha.id(), adminAlpha.id(),
            client.registeredClientId(), Set.of(viewer.id(), editor.id())
        );

        Map<String, Object> claims = runFlowAndExtractJwtClaims(
            alphaTenant.slug(), client, "alice@example.test", "password"
        );

        assertThat(claims.get("tenant")).isEqualTo("gate-alpha");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        assertThat(roles).containsExactly("editor", "viewer");
    }

    @Test
    @DisplayName("A user from tenant Beta logging into tenant Alpha's URL is rejected by TenantAccessFilter before the gate ever runs")
    void crossTenantUserIsRejectedBeforeReachingGate() throws Exception {
        // bob (Tenant Beta) attempting to login against Tenant Alpha's URL is
        // force-logged-out by TenantAccessFilter from v1 — login fails outright,
        // so the gate never gets to evaluate. We assert the failure so the
        // existing isolation contract is documented alongside the new gate.
        TenantClient alphaClient = createPkceClient(alphaApp, alphaTenant, "cross-tenant-client");
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            betaApp.id(), betaTenant.id(), bobBeta.id(), adminBeta.id(),
            createPkceClient(betaApp, betaTenant, "beta-client").registeredClientId(),
            Set.of()
        );

        MockHttpSession session = new MockHttpSession();
        String authzUri = authorizeUri(alphaTenant.slug(), alphaClient, pkceChallenge());
        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());

        // bob's credentials don't validate against Tenant Alpha's User pool.
        mockMvc.perform(post("/t/" + alphaTenant.slug() + "/login")
                .param("email", "bob@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(result ->
                assertThat(result.getResponse().getHeader("Location")).contains("error")
            );
    }

    private TenantClient createPkceClient(Application application, Tenant tenant, String name) {
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
            null, internalId, application.id(), tenant.id(), name, false
        ));
    }

    private String runFlowToAuthorizeRedirect(
        String slug, TenantClient client, String email, String password
    ) throws Exception {
        Pkce pkce = pkce();
        MockHttpSession session = new MockHttpSession();
        String authzUri = authorizeUri(slug, client, pkce.challenge());

        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/" + slug + "/login")
                .param("email", email).param("password", password)
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        return result.getResponse().getHeader("Location");
    }

    private Map<String, Object> runFlowAndExtractJwtClaims(
        String slug, TenantClient client, String email, String password
    ) throws Exception {
        Pkce pkce = pkce();
        MockHttpSession session = new MockHttpSession();
        String authzUri = authorizeUri(slug, client, pkce.challenge());

        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + slug + "/login")
                .param("email", email).param("password", password)
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());
        MvcResult codeResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String code = queryParams(codeResult.getResponse().getHeader("Location")).get("code");
        assertThat(code).isNotBlank();

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, client.registeredClientId()
        );
        String tokenJson = mockMvc.perform(post("/t/" + slug + "/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .param("code_verifier", pkce.verifier())
                .param("client_id", oauthClientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        return SignedJWT.parse(accessToken).getJWTClaimsSet().getClaims();
    }

    private String authorizeUri(String slug, TenantClient client, String challenge) {
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, client.registeredClientId()
        );
        return UriComponentsBuilder.fromPath("/t/" + slug + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "s1")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
    }

    private static Map<String, String> queryParams(String location) {
        return UriComponentsBuilder.fromUriString(location).build()
            .getQueryParams().toSingleValueMap();
    }

    private static Pkce pkce() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return new Pkce(verifier, challenge);
    }

    private static String pkceChallenge() throws Exception {
        return pkce().challenge();
    }

    private record Pkce(String verifier, String challenge) {}
}

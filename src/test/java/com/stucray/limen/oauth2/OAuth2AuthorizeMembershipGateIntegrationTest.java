package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembership;
import com.stucray.limen.memberships.ApplicationMembershipRepository;
import com.stucray.limen.memberships.ApplicationMembershipService;
import com.stucray.limen.memberships.ClientMembership;
import com.stucray.limen.memberships.ClientMembershipRepository;
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
    @Autowired ApplicationMembershipRepository applicationMembershipRepository;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired ClientMembershipRepository clientMembershipRepository;
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
    @DisplayName("Issue #309: UI-driven flow — create client + grant CM via controllers, then /oauth2/authorize must issue a code")
    void issue309_uiDrivenFullFlow_authorizeIssuesCode() throws Exception {
        // Owner needs an AM for the parent app to appear in the CM picker.
        // Issue's repro: "a member of the application so appeared in the pick list".
        // (Fixture state via repository write — spring-boot-tests.md.)
        applicationMembershipRepository.save(new ApplicationMembership(
            null, aliceAlpha.id(), alphaApp.id(), java.time.LocalDateTime.now(), aliceAlpha.id(),
            java.util.Set.of()
        ));

        // 1. Owner logs into management console.
        MockHttpSession mgmtSession = new MockHttpSession();
        mockMvc.perform(post("/manage/t/" + alphaTenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(mgmtSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        // 2. Owner creates a new PUBLIC PKCE client through the controller.
        mockMvc.perform(post("/manage/t/" + alphaTenant.slug() + "/applications/" + alphaApp.id() + "/clients")
                .session(mgmtSession).with(csrf())
                .param("displayName", "ui-flow-client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("postLogoutRedirectUris", "")
                .param("scopes", "openid")
                .param("requirePkce", "true")
                .param("requireConsent", "false")
                .param("confidential", "false")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30")
                .param("reuseRefreshTokens", "false"))
            .andExpect(status().is3xxRedirection());

        String registeredClientId = jdbcTemplate.queryForObject(
            "SELECT id FROM oauth2_registered_client WHERE client_name = ?",
            String.class, "ui-flow-client");
        assertThat(registeredClientId).isNotNull();
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, registeredClientId);

        // 3. Owner grants CM to themselves via the controller.
        mockMvc.perform(post("/manage/t/" + alphaTenant.slug() + "/applications/" + alphaApp.id()
                + "/clients/" + registeredClientId + "/members")
                .session(mgmtSession).with(csrf())
                .param("userId", aliceAlpha.id().toString()))
            .andExpect(status().is3xxRedirection());

        // Sanity: gate's exact lookup shape must see the CM.
        Integer cmCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM client_membership cm
              JOIN client_metadata m ON m.id = cm.client_metadata_id
             WHERE cm.user_id = ?
               AND m.registered_client_id = ?
               AND m.tenant_id = ?
            """,
            Integer.class, aliceAlpha.id(), registeredClientId, alphaTenant.id());
        assertThat(cmCount).as("CM row must be visible to the gate query").isEqualTo(1);

        // 4. From a fresh session, drive /oauth2/authorize.
        Pkce pkce = pkce();
        MockHttpSession oauthSession = new MockHttpSession();
        String authzUri = UriComponentsBuilder.fromPath("/t/" + alphaTenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/cb")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "s1")
            .queryParam("code_challenge", pkce.challenge())
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        mockMvc.perform(get(authzUri).session(oauthSession))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + alphaTenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(oauthSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        MvcResult result = mockMvc.perform(get(authzUri).session(oauthSession))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = result.getResponse().getHeader("Location");
        Map<String, String> params = queryParams(location);
        assertThat(params)
            .as("Expected /oauth2/authorize to issue a code after a UI-driven CM grant; got: " + location)
            .containsKey("code")
            .doesNotContainKey("error");
    }

    @Test
    @DisplayName("Issue #309 deny-then-grant-then-retry: after CM is added mid-session, the next /oauth2/authorize must issue a code (not stale access_denied)")
    void issue309_denyThenGrantThenRetry_pickedUpOnRetry() throws Exception {
        // Owner has AM (would be present already in the user's repro since
        // they were able to use the picker).
        applicationMembershipRepository.save(new ApplicationMembership(
            null, aliceAlpha.id(), alphaApp.id(), java.time.LocalDateTime.now(), aliceAlpha.id(),
            java.util.Set.of()
        ));

        // Client exists but has NO Client Membership for alice.
        TenantClient client = createPkceClient(alphaApp, alphaTenant, "retry-client");

        // Step 1: alice (in incognito) hits /oauth2/authorize, logs in, hits
        // again -- the gate denies with access_denied.
        Pkce pkce = pkce();
        MockHttpSession session = new MockHttpSession();
        String authzUri = authorizeUri(alphaTenant.slug(), client, pkce.challenge());

        mockMvc.perform(get(authzUri).session(session)).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + alphaTenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());
        MvcResult firstDenial = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String firstLocation = firstDenial.getResponse().getHeader("Location");
        assertThat(queryParams(firstLocation))
            .as("Step 1 must surface access_denied")
            .containsEntry("error", "access_denied");

        // Step 2: operator grants CM (AM was already present, mirroring the
        // user's repro where alice was in the picker). Fixture-via-repo write
        // so the call is fully outside the OAuth session that just denied.
        ApplicationMembership aliceAm = applicationMembershipRepository
            .findByUserIdAndApplicationId(aliceAlpha.id(), alphaApp.id()).orElseThrow();
        clientMembershipRepository.save(new ClientMembership(
            null, aliceAlpha.id(), client.id(), aliceAm.id(),
            java.time.LocalDateTime.now(), adminAlpha.id(), java.util.Set.of()
        ));

        // Step 3: alice retries /oauth2/authorize in the SAME browser session.
        // Use a fresh PKCE challenge to simulate a fresh OAuth request from
        // the consumer (otherwise SAS rejects a reused challenge in some
        // configurations).
        Pkce pkce2 = pkce();
        String authzUri2 = authorizeUri(alphaTenant.slug(), client, pkce2.challenge());
        MvcResult retry = mockMvc.perform(get(authzUri2).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String retryLocation = retry.getResponse().getHeader("Location");
        Map<String, String> retryParams = queryParams(retryLocation);
        assertThat(retryParams)
            .as("After CM is granted, the very next /oauth2/authorize MUST issue a code; got: " + retryLocation)
            .containsKey("code")
            .doesNotContainKey("error");
    }

    @Test
    @DisplayName("Issue #309 deny-then-grant-then-retry against a CONFIDENTIAL (BFF-shape) client: CM grant picked up on retry")
    void issue309_denyThenGrantThenRetry_confidentialClient() throws Exception {
        applicationMembershipRepository.save(new ApplicationMembership(
            null, aliceAlpha.id(), alphaApp.id(), java.time.LocalDateTime.now(), aliceAlpha.id(),
            java.util.Set.of()
        ));

        // BFF-shape client: confidential, no PKCE, single registered redirect.
        String internalId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(oauthClientId)
            .clientSecret(passwordEncoder.encode("bff-secret"))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/bff/callback")
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(false)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        TenantClient client = tenantClientRepository.save(new TenantClient(
            null, internalId, alphaApp.id(), alphaTenant.id(), "bff-shape-client", true
        ));

        MockHttpSession session = new MockHttpSession();
        String authzUri = UriComponentsBuilder.fromPath("/t/" + alphaTenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/bff/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "bff-s1")
            .build().toUriString();

        // Step 1: deny.
        mockMvc.perform(get(authzUri).session(session)).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + alphaTenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());
        MvcResult firstDenial = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        assertThat(queryParams(firstDenial.getResponse().getHeader("Location")))
            .containsEntry("error", "access_denied");

        // Step 2: grant CM out-of-band.
        ApplicationMembership aliceAm = applicationMembershipRepository
            .findByUserIdAndApplicationId(aliceAlpha.id(), alphaApp.id()).orElseThrow();
        clientMembershipRepository.save(new ClientMembership(
            null, aliceAlpha.id(), client.id(), aliceAm.id(),
            java.time.LocalDateTime.now(), adminAlpha.id(), java.util.Set.of()
        ));

        // Step 3: retry — same URL, same session, no PKCE involved.
        MvcResult retry = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String retryLocation = retry.getResponse().getHeader("Location");
        Map<String, String> retryParams = queryParams(retryLocation);
        assertThat(retryParams)
            .as("Confidential-client retry after CM grant must issue a code; got: " + retryLocation)
            .containsKey("code")
            .doesNotContainKey("error");
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

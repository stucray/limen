package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Per-tenant OAuth2 routing under /t/{slug}/: discovery, token, JWKS, userinfo, plus cross-tenant client isolation")
class TenantOAuth2RoutingIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    Tenant alphaCorpTenant;
    Tenant betaCorpTenant;
    Application alphaApp;
    User alphaAdmin;

    @BeforeEach
    void setUp() {
        // Order matters: client_membership_role → role uses ON DELETE RESTRICT,
        // so any rows left from earlier tests must be cleared before deleting
        // applications would cascade through role.
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        alphaCorpTenant = tenantProvisioningService.createTenant("alpha-corp", "Alpha Corp");
        betaCorpTenant = tenantProvisioningService.createTenant("beta-corp", "Beta Corp");
        alphaApp = applicationRepository.save(new Application(
            null, alphaCorpTenant.id(), "Alpha App", "Test app", LocalDateTime.now()
        ));
        alphaAdmin = userRepository.save(new User(
            null, alphaCorpTenant.id(), "alpha-admin@example.test",
            passwordEncoder.encode("password"),
            true, false, true, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("/t/{slug}/.well-known/openid-configuration advertises tenant-prefixed issuer, token, jwks, and authorization endpoints")
    void discoveryDocumentHasTenantIssuer() throws Exception {
        mockMvc.perform(get("/t/alpha-corp/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost/t/alpha-corp"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost/t/alpha-corp/oauth2/token"))
            .andExpect(jsonPath("$.jwks_uri").value("http://localhost/t/alpha-corp/oauth2/jwks"))
            .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost/t/alpha-corp/oauth2/authorize"));
    }

    @Test
    @DisplayName("Discovery on an unknown tenant slug returns 404")
    void unknownTenantSlugReturns404() throws Exception {
        mockMvc.perform(get("/t/no-such-tenant/.well-known/openid-configuration"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Discovery on a SUSPENDED tenant returns 403 — the tenant exists but is shut off")
    void suspendedTenantReturns403() throws Exception {
        tenantRepository.save(new Tenant(
            null, "suspended-co", "Suspended Co", TenantStatus.SUSPENDED, LocalDateTime.now()
        ));

        mockMvc.perform(get("/t/suspended-co/.well-known/openid-configuration"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("client_credentials token flow at /t/{slug}/oauth2/token succeeds for a client created under that tenant")
    void clientCredentialsTokenFlowSucceedsForTenantClient() throws Exception {
        SeededConfidentialClient result = seedConfidentialClient(alphaApp.id(), alphaCorpTenant.id(), "m2m-client");

        String clientId = result.client().registeredClientId();
        String rawSecret = result.rawSecret();

        // Look up the actual OAuth2 client_id (UUID) registered with SAS
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, clientId
        );

        String tokenResponse = mockMvc.perform(post("/t/alpha-corp/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, rawSecret)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(tokenResponse);
        assertThat(json.get("access_token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("A client registered under tenant alpha cannot exchange credentials at tenant beta's /oauth2/token — the request is rejected as 401")
    void crossTenantClientRejected() throws Exception {
        // Register a client under alpha-corp
        SeededConfidentialClient result = seedConfidentialClient(alphaApp.id(), alphaCorpTenant.id(), "alpha-m2m");

        String clientId = result.client().registeredClientId();
        String rawSecret = result.rawSecret();

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, clientId
        );

        // Attempt to use it via beta-corp's endpoint — must be rejected
        mockMvc.perform(post("/t/beta-corp/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, rawSecret)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("client_credentials access token carries tenant + iss claims and an empty roles array")
    void clientCredentialsTokenIncludesTenantAndRolesClaims() throws Exception {
        SeededConfidentialClient result = seedConfidentialClient(alphaApp.id(), alphaCorpTenant.id(), "claims-m2m");

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        String tokenJson = mockMvc.perform(post("/t/alpha-corp/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        Map<String, Object> claims = SignedJWT.parse(accessToken).getJWTClaimsSet().getClaims();

        assertThat(claims.get("tenant")).isEqualTo("alpha-corp");
        assertThat(claims.get("roles")).isInstanceOf(List.class);
        assertThat((List<?>) claims.get("roles")).isEmpty();
        assertThat(claims.get("iss")).asString().isEqualTo("http://localhost/t/alpha-corp");
    }

    @Test
    @DisplayName("Full authorization-code + PKCE flow under /t/{slug}/ yields a token with tenant + iss claims for the end user")
    void authorizationCodePkceFlowProducesTokenWithTenantClaims() throws Exception {
        User alice = userRepository.save(new User(
            null, alphaCorpTenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));

        // Create public PKCE client with consent disabled (bypasses consent step in test)
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
        tenantClientRepository.save(new TenantClient(
            null, internalId, alphaApp.id(), alphaCorpTenant.id(), "PKCE Test Client", false
        ));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            alphaApp.id(), alphaCorpTenant.id(), alice.id(), alphaAdmin.id(),
            internalId, Set.of()
        );

        // PKCE code verifier + challenge
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        // 1. GET /t/alpha-corp/oauth2/authorize → redirect to /t/alpha-corp/login
        String authzUri = UriComponentsBuilder.fromPath("/t/alpha-corp/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        MockHttpSession session = new MockHttpSession();
        MvcResult authzResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String loginLocation = authzResult.getResponse().getHeader("Location");
        assertThat(loginLocation).contains("/t/alpha-corp/login");

        // 2. POST /t/alpha-corp/login → redirect to /t/alpha-corp/oauth2/authorize
        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test")
                .param("password", "password")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String postLoginLocation = loginResult.getResponse().getHeader("Location");
        assertThat(postLoginLocation).contains("/t/alpha-corp/oauth2/authorize");

        // 3. GET /t/alpha-corp/oauth2/authorize (authenticated) → redirect with authorization code
        MvcResult codeResult = mockMvc.perform(get(postLoginLocation).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String codeLocation = codeResult.getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(codeLocation).build()
            .getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        // 4. POST /t/alpha-corp/oauth2/token → access token
        String tokenJson = mockMvc.perform(post("/t/alpha-corp/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .param("code_verifier", codeVerifier)
                .param("client_id", clientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        Map<String, Object> claims = SignedJWT.parse(accessToken).getJWTClaimsSet().getClaims();

        assertThat(claims.get("tenant")).isEqualTo("alpha-corp");
        assertThat(claims.get("roles")).isInstanceOf(List.class);
        assertThat((List<?>) claims.get("roles")).isEmpty();
        assertThat(claims.get("iss")).asString().isEqualTo("http://localhost/t/alpha-corp");
    }

    @Test
    @DisplayName("Each tenant's JWKS endpoint serves only its own signing keys — alpha's token verifies against alpha's JWKS but not beta's")
    void tenantJwksEndpointsServeIsolatedKeys() throws Exception {
        SeededConfidentialClient result = seedConfidentialClient(alphaApp.id(), alphaCorpTenant.id(), "isolation-m2m");
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        String tokenJson = mockMvc.perform(post("/t/alpha-corp/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        SignedJWT jwt = SignedJWT.parse(accessToken);
        String kid = jwt.getHeader().getKeyID();

        String alphaJwksJson = mockMvc.perform(get("/t/alpha-corp/oauth2/jwks"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JWKSet alphaJwks = JWKSet.parse(alphaJwksJson);
        JWK alphaMatch = alphaJwks.getKeyByKeyId(kid);
        assertThat(alphaMatch).as("alpha JWKS contains the signing kid").isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier((RSAKey) alphaMatch)))
            .as("alpha-issued token verifies against alpha's JWKS")
            .isTrue();

        String betaJwksJson = mockMvc.perform(get("/t/beta-corp/oauth2/jwks"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JWKSet betaJwks = JWKSet.parse(betaJwksJson);
        assertThat(betaJwks.getKeyByKeyId(kid))
            .as("beta JWKS does NOT contain alpha's signing kid")
            .isNull();
        RSAKey betaActiveKey = (RSAKey) betaJwks.getKeys().getFirst();
        assertThat(jwt.verify(new RSASSAVerifier(betaActiveKey)))
            .as("alpha-issued token does not verify against beta's active key")
            .isFalse();
    }

    @Test
    @DisplayName("/t/{slug}/userinfo accepts a Bearer token from the same tenant and returns the user's claims")
    void userinfoEndpointReturnsClaimsForTenantUser() throws Exception {
        User bob = userRepository.save(new User(
            null, alphaCorpTenant.id(), "bob@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));

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
        tenantClientRepository.save(new TenantClient(
            null, internalId, alphaApp.id(), alphaCorpTenant.id(), "UserInfo Test Client", false
        ));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            alphaApp.id(), alphaCorpTenant.id(), bob.id(), alphaAdmin.id(),
            internalId, Set.of()
        );

        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(UriComponentsBuilder.fromPath("/t/alpha-corp/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", "http://localhost/callback")
                .queryParam("scope", OidcScopes.OPENID)
                .queryParam("state", "s1")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().toUriString()).session(session))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "bob@example.test")
                .param("password", "password")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult codeResult = mockMvc.perform(get(UriComponentsBuilder.fromPath("/t/alpha-corp/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", "http://localhost/callback")
                .queryParam("scope", OidcScopes.OPENID)
                .queryParam("state", "s1")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().toUriString()).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String code = UriComponentsBuilder.fromUriString(codeResult.getResponse().getHeader("Location"))
            .build().getQueryParams().getFirst("code");

        String tokenJson = mockMvc.perform(post("/t/alpha-corp/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .param("code_verifier", codeVerifier)
                .param("client_id", clientId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();

        mockMvc.perform(get("/t/alpha-corp/userinfo")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").isNotEmpty());
    }

    private record SeededConfidentialClient(TenantClient client, String rawSecret) {}

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    private SeededConfidentialClient seedConfidentialClient(Long applicationId, Long tenantId, String name) {
        String registeredClientId = UUID.randomUUID().toString();
        String rawSecret = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
            .clientId(UUID.randomUUID().toString())
            .clientName(name)
            .clientSecret(passwordEncoder.encode(rawSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("read")
            .clientSettings(ClientSettings.builder()
                .requireProofKey(false)
                .requireAuthorizationConsent(true)
                .build())
            .build();
        registeredClientRepository.save(rc);
        TenantClient tc = tenantClientRepository.save(new TenantClient(
            null, registeredClientId, applicationId, tenantId, name, true));
        return new SeededConfidentialClient(tc, rawSecret);
    }
}

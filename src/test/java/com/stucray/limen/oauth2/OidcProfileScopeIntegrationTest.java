package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OIDC profile scope: discovery, ID token, and /userinfo agree end-to-end")
class OidcProfileScopeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired UserRepository userRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Tenant tenant;
    private User aliceWithName;
    private User noNameUser;
    private User adminUser;
    private String registeredOauthClientId;

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

        tenant = tenantProvisioningService.createTenant("acme", "Acme Inc");
        Application app = applicationRepository.save(new Application(
            null, tenant.id(), "Acme App", "Acme test app", LocalDateTime.now()
        ));
        adminUser = userRepository.save(new User(
            null, tenant.id(), "admin@acme.test",
            passwordEncoder.encode("password"),
            true, false, true, true, LocalDateTime.now()
        ));
        aliceWithName = userRepository.save(new User(
            null, tenant.id(), "alice@acme.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ).withFullName("Alice Example"));
        noNameUser = userRepository.save(new User(
            null, tenant.id(), "noname@acme.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));

        String internalId = UUID.randomUUID().toString();
        registeredOauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(registeredOauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, internalId, app.id(), tenant.id(), "PKCE Profile Client", false
        ));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), aliceWithName.id(), adminUser.id(),
            internalId, Set.of()
        );
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), noNameUser.id(), adminUser.id(),
            internalId, Set.of()
        );
    }

    @Test
    @DisplayName("Auth-code flow with scope=openid profile puts name into the ID token, matching User.fullName")
    void idTokenIncludesNameWhenProfileScopeGranted() throws Exception {
        TokenResponse tokens = runAuthCodeFlowAs(aliceWithName.email(), "openid profile");

        Map<String, Object> idTokenClaims = SignedJWT.parse(tokens.idToken()).getJWTClaimsSet().getClaims();

        assertThat(idTokenClaims).containsEntry("name", "Alice Example");
    }

    @Test
    @DisplayName("Auth-code flow with scope=openid profile exposes name at /userinfo")
    void userinfoIncludesNameWhenProfileScopeGranted() throws Exception {
        TokenResponse tokens = runAuthCodeFlowAs(aliceWithName.email(), "openid profile");

        mockMvc.perform(get("/t/acme/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").exists())
            .andExpect(jsonPath("$.name").value("Alice Example"));
    }

    @Test
    @DisplayName("Auth-code flow with scope=openid alone (profile NOT granted) omits name from the ID token")
    void idTokenOmitsNameWhenProfileScopeNotGranted() throws Exception {
        TokenResponse tokens = runAuthCodeFlowAs(aliceWithName.email(), "openid");

        Map<String, Object> idTokenClaims = SignedJWT.parse(tokens.idToken()).getJWTClaimsSet().getClaims();

        assertThat(idTokenClaims).doesNotContainKey("name");
    }

    @Test
    @DisplayName("Auth-code flow with scope=openid alone (profile NOT granted) omits name from /userinfo")
    void userinfoOmitsNameWhenProfileScopeNotGranted() throws Exception {
        TokenResponse tokens = runAuthCodeFlowAs(aliceWithName.email(), "openid");

        mockMvc.perform(get("/t/acme/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").exists())
            .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    @DisplayName("A user with null full_name gets ID token + /userinfo with NO name claim — not name: null or name: empty")
    void userWithNullFullNameGetsNoNameClaim() throws Exception {
        TokenResponse tokens = runAuthCodeFlowAs(noNameUser.email(), "openid profile");

        Map<String, Object> idTokenClaims = SignedJWT.parse(tokens.idToken()).getJWTClaimsSet().getClaims();
        assertThat(idTokenClaims).doesNotContainKey("name");

        mockMvc.perform(get("/t/acme/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").exists())
            .andExpect(jsonPath("$.name").doesNotExist());
    }

    private TokenResponse runAuthCodeFlowAs(String email, String scope) throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        String authzUri = UriComponentsBuilder.fromPath("/t/acme/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", registeredOauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", scope)
            .queryParam("state", "test-state")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(authzUri).session(session)).andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/acme/login")
                .param("email", email)
                .param("password", "password")
                .session(session)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult codeResult = mockMvc.perform(get("/t/acme/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", registeredOauthClientId)
                .queryParam("redirect_uri", "http://localhost/callback")
                .queryParam("scope", scope)
                .queryParam("state", "test-state")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String codeLocation = codeResult.getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(codeLocation).build()
            .getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        String tokenJson = mockMvc.perform(post("/t/acme/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .param("code_verifier", codeVerifier)
                .param("client_id", registeredOauthClientId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(tokenJson);
        return new TokenResponse(
            json.get("access_token").asText(),
            json.get("id_token").asText()
        );
    }

    private record TokenResponse(String accessToken, String idToken) {}
}

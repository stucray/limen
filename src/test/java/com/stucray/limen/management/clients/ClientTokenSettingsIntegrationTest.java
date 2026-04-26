package com.stucray.limen.management.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
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
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"LIMEN_SIGNING_KEY_PATH=./target/test-signing-key.jwk"})
@AutoConfigureMockMvc
class ClientTokenSettingsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    Tenant tenant;
    Application app;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenant = tenantRepository.save(new Tenant(null, "token-test", "Token Test", TenantStatus.ACTIVE, LocalDateTime.now()));
        app = applicationRepository.save(new Application(null, tenant.id(), "Test App", null, LocalDateTime.now()));
        userRepository.save(new User(null, tenant.id(), "alice", passwordEncoder.encode("password"), true, false, false, LocalDateTime.now()));
    }

    @Test
    void accessTokenTtlIsReflectedInIssuedToken() throws Exception {
        long ttlMinutes = 2;
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            app.id(), tenant.id(), "ttl-client",
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, true, ttlMinutes, 30, false
        );

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        String tokenJson = mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        SignedJWT jwt = SignedJWT.parse(accessToken);
        Date iat = jwt.getJWTClaimsSet().getIssueTime();
        Date exp = jwt.getJWTClaimsSet().getExpirationTime();

        long actualTtlSeconds = (exp.getTime() - iat.getTime()) / 1000;
        assertThat(actualTtlSeconds).isEqualTo(ttlMinutes * 60);
    }

    @Test
    void pkceRequiredClientRejectsTokenExchangeWithoutCodeVerifier() throws Exception {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            app.id(), tenant.id(), "pkce-client",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/callback"), Set.of(), Set.of(OidcScopes.OPENID),
            true, true, 5, 30, false
        );

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        // Drive the auth code flow to get a code (with PKCE)
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(UriComponentsBuilder.fromPath("/t/token-test/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", oauthClientId)
                .queryParam("redirect_uri", "http://localhost/callback")
                .queryParam("scope", OidcScopes.OPENID)
                .queryParam("state", "s1")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().toUriString()).session(session))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/t/token-test/login")
                .param("username", "alice").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult codeResult = mockMvc.perform(get(UriComponentsBuilder.fromPath("/t/token-test/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", oauthClientId)
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
        assertThat(code).isNotBlank();

        // Exchange code without code_verifier — must fail
        mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refreshTokenRotationIssuesNewTokenAndInvalidatesOld() throws Exception {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            app.id(), tenant.id(), "rotation-client",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN),
            Set.of("http://localhost/callback"), Set.of(), Set.of(OidcScopes.OPENID),
            false, true, 5, 30, false  // reuseRefreshTokens=false → rotation
        );

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        // Auth code flow (no PKCE since client is confidential and requirePkce=false, but SAS requires consent bypass)
        // Use a separate public client approach: update to disable consent, or just use httpBasic auth
        String firstRefreshToken = doAuthCodeFlowAndGetRefreshToken(oauthClientId, result.rawSecret(), false);

        // First refresh — should succeed and return a new refresh token
        String secondRefreshTokenResponse = mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", firstRefreshToken)
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();

        String secondRefreshToken = objectMapper.readTree(secondRefreshTokenResponse).get("refresh_token").asText();
        assertThat(secondRefreshToken).isNotBlank();

        // Use old (rotated) refresh token — must be rejected
        mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", firstRefreshToken)
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reuseRefreshTokensAllowsRepeatedUseOfSameToken() throws Exception {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            app.id(), tenant.id(), "reuse-client",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN),
            Set.of("http://localhost/callback"), Set.of(), Set.of(OidcScopes.OPENID),
            false, true, 5, 30, true  // reuseRefreshTokens=true
        );

        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, result.client().registeredClientId()
        );

        String refreshToken = doAuthCodeFlowAndGetRefreshToken(oauthClientId, result.rawSecret(), true);

        // Both uses of the same refresh token should succeed
        mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk());

        mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk());
    }

    @Test
    void updatingTokenSettingsIsReflectedInSubsequentTokens() throws Exception {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            app.id(), tenant.id(), "update-client",
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, true, 60, 30, false
        );

        String registeredClientId = result.client().registeredClientId();
        String oauthClientId = jdbcTemplate.queryForObject(
            "SELECT client_id FROM oauth2_registered_client WHERE id = ?",
            String.class, registeredClientId
        );

        // Update TTL from 60 minutes to 10 minutes
        clientManagementService.updateClientSettings(registeredClientId, tenant.id(), 10, 30, false, false);

        String tokenJson = mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read")
                .with(httpBasic(oauthClientId, result.rawSecret())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(tokenJson).get("access_token").asText();
        SignedJWT jwt = SignedJWT.parse(accessToken);
        long actualTtlSeconds = (jwt.getJWTClaimsSet().getExpirationTime().getTime()
            - jwt.getJWTClaimsSet().getIssueTime().getTime()) / 1000;

        assertThat(actualTtlSeconds).isEqualTo(10 * 60);
    }

    private String doAuthCodeFlowAndGetRefreshToken(String oauthClientId, String rawSecret, boolean reuseMode) throws Exception {
        MockHttpSession session = new MockHttpSession();

        // Auth code flow without PKCE (confidential client, requirePkce=false)
        // SAS requires consent; bypass by sending an existing session with consent pre-approved
        // We disable consent for test clients by not requiring it — but the service sets requireAuthorizationConsent(true).
        // Drive the full flow: authorize → login → authorize (redirects with code if consent already given, or consent page)
        // Since consent is required, we need to handle the consent step.

        String authzUri = UriComponentsBuilder.fromPath("/t/token-test/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", oauthClientId)
            .queryParam("redirect_uri", "http://localhost/callback")
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "s1")
            .build().toUriString();

        // Hit authorize — redirects to login
        mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection());

        // Login
        mockMvc.perform(post("/t/token-test/login")
                .param("username", "alice").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        // Hit authorize again (authenticated) — redirects to consent page
        MvcResult authzResult = mockMvc.perform(get(authzUri).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = authzResult.getResponse().getHeader("Location");

        // If it's already a code redirect (e.g. consent pre-given), extract code; otherwise post consent
        if (location != null && location.contains("code=")) {
            String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
            return exchangeCodeForRefreshToken(oauthClientId, rawSecret, code);
        }

        // Post consent approval
        MvcResult consentResult = mockMvc.perform(post("/t/token-test/oauth2/authorize")
                .param("client_id", oauthClientId)
                .param("state", "s1")
                .param("scope", OidcScopes.OPENID)
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String codeLoc = consentResult.getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(codeLoc).build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        return exchangeCodeForRefreshToken(oauthClientId, rawSecret, code);
    }

    private String exchangeCodeForRefreshToken(String oauthClientId, String rawSecret, String code) throws Exception {
        String tokenJson = mockMvc.perform(post("/t/token-test/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", "http://localhost/callback")
                .with(httpBasic(oauthClientId, rawSecret)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(tokenJson).get("refresh_token").asText();
    }
}

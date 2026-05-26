package com.stucray.limen.oauth2.sas;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end wiring test for {@link LoopbackAwareOidcLogoutValidator}. Proves
 * the custom validator is installed on SAS's {@code OidcLogoutAuthenticationProvider}
 * and that its verdict propagates to the {@code /connect/logout} HTTP response.
 *
 * <p>The validator's decision logic is exhaustively covered by
 * {@link LoopbackAwareOidcLogoutValidatorTest}; these tests verify wiring at the
 * HTTP boundary with one positive case (loopback wildcard) and one negative
 * case ({@code localhost} exact-only). PRD #316 / slice #318.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/connect/logout wiring: LoopbackAwareOidcLogoutValidator's verdict reaches the HTTP response")
class OidcLogoutLoopbackIntegrationTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        tenant = tenantProvisioningService.createTenant("logout-tenant", "Logout Tenant");
        application = applicationRepository.save(new Application(
            null, tenant.id(), "Logout App", null, LocalDateTime.now()
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
    @DisplayName("HTTP 127.0.0.1 post-logout URI on a different port redirects to the requested URI (loopback wildcard fires through the wired validator)")
    void httpLoopbackPortMismatchRedirectsToRequestedUri() throws Exception {
        String registeredCallback = "http://127.0.0.1:8080/callback";
        String registeredPostLogout = "http://127.0.0.1:8080/logged-out";
        String requestedPostLogout = "http://127.0.0.1:54321/logged-out";

        ClientFixture fixture = registerClientAndGrantMembership(registeredCallback, registeredPostLogout);
        String idToken = signInAndGetIdToken(fixture);

        String logoutUri = UriComponentsBuilder.fromPath("/t/" + tenant.slug() + "/connect/logout")
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", requestedPostLogout)
            .queryParam("state", "logout-s1")
            .build().toUriString();

        MvcResult result = mockMvc.perform(get(logoutUri).session(fixture.session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location)
            .as("SAS must redirect to the *requested* loopback post-logout URI (port from %s), proving the custom validator is wired and the loopback branch fires; got: %s",
                requestedPostLogout, location)
            .startsWith(requestedPostLogout);
        assertThat(location).contains("state=logout-s1");
    }

    @Test
    @DisplayName("localhost post-logout URI on a different port is rejected (no port wildcarding for localhost — validator's verdict propagates as an error)")
    void localhostPortMismatchRejected() throws Exception {
        String registeredCallback = "http://localhost:8080/callback";
        String registeredPostLogout = "http://localhost:8080/logged-out";
        String requestedPostLogout = "http://localhost:54321/logged-out";

        ClientFixture fixture = registerClientAndGrantMembership(registeredCallback, registeredPostLogout);
        String idToken = signInAndGetIdToken(fixture);

        String logoutUri = UriComponentsBuilder.fromPath("/t/" + tenant.slug() + "/connect/logout")
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", requestedPostLogout)
            .queryParam("state", "logout-s2")
            .build().toUriString();

        // SAS's failure handler renders the OAuth2 error on a non-redirect path
        // (default behaviour for /connect/logout). We assert non-2xx-non-3xx
        // matching `Location` of the requested URI — i.e. the requested URI is
        // NOT honoured.
        MvcResult result = mockMvc.perform(get(logoutUri).session(fixture.session))
            .andReturn();
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getHeader("Location");
        assertThat(status)
            .as("localhost port mismatch must not be wildcarded; expected non-success status, got %s with Location=%s",
                status, location)
            .isGreaterThanOrEqualTo(400);
    }

    private ClientFixture registerClientAndGrantMembership(String callbackUri, String postLogoutUri) {
        String internalId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(callbackUri)
            .postLogoutRedirectUri(postLogoutUri)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        TenantClient client = tenantClientRepository.save(new TenantClient(
            null, internalId, application.id(), tenant.id(), "logout-client", false
        ));

        ApplicationMembership am = applicationMembershipRepository.save(new ApplicationMembership(
            null, user.id(), application.id(), LocalDateTime.now(), admin.id(), Set.of()
        ));
        clientMembershipRepository.save(new ClientMembership(
            null, user.id(), client.id(), am.id(),
            LocalDateTime.now(), admin.id(), Set.of()
        ));

        return new ClientFixture(client, oauthClientId, callbackUri, new MockHttpSession());
    }

    private String signInAndGetIdToken(ClientFixture fixture) throws Exception {
        Pkce pkce = pkce();
        String authzUri = UriComponentsBuilder.fromPath("/t/" + tenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", fixture.oauthClientId)
            .queryParam("redirect_uri", fixture.callbackUri)
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", "authz-s1")
            .queryParam("code_challenge", pkce.challenge())
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        mockMvc.perform(get(authzUri).session(fixture.session))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/t/" + tenant.slug() + "/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(fixture.session).with(csrf()))
            .andExpect(status().is3xxRedirection());
        MvcResult codeResult = mockMvc.perform(get(authzUri).session(fixture.session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String code = queryParams(codeResult.getResponse().getHeader("Location")).get("code");
        assertThat(code).as("authorization code is required to mint an id_token for the logout test").isNotBlank();

        String tokenJson = mockMvc.perform(post("/t/" + tenant.slug() + "/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", fixture.callbackUri)
                .param("code_verifier", pkce.verifier())
                .param("client_id", fixture.oauthClientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id_token").exists())
            .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(tokenJson).get("id_token").asText();
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

    private record Pkce(String verifier, String challenge) { }

    private record ClientFixture(
        TenantClient client, String oauthClientId, String callbackUri, MockHttpSession session
    ) { }
}

package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
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
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level pin for issue #293: a runtime fault inside SAS's
 * token-issuance path (here injected via a {@code JWKSource} that throws
 * {@link JwtEncodingException} — mirroring the deployed-image symptom
 * "Failed to select a JWK signing key → Unable to invoke Cipher due to bad
 * padding") must surface as RFC 6749 §5.2 JSON, not the catch-all chain's
 * {@code 403 [no body]}. The unit-level test
 * {@link com.stucray.limen.oauth2.sas.SasServerErrorTranslationFilterTest}
 * pins the filter's own contract; this test pins the wiring + dispatch.
 */
@Import({TestcontainersConfiguration.class, Issue293ConfidentialPkceTokenIntegrationTest.BrokenJwkSourceConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Issue #293: a runtime fault during /oauth2/token issuance returns RFC 6749 §5.2 server_error JSON, not 403 [no body]")
class Issue293ConfidentialPkceTokenIntegrationTest {

    @LocalServerPort int port;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SLUG = "overround";
    private static final String REDIRECT_URI = "http://localhost:8091/login/oauth2/code/bff-client";
    private static final String END_USER_EMAIL = "alice@example.test";
    private static final String PASSWORD = "password";

    Tenant tenant;
    Application app;
    User admin;
    User alice;
    String clientId;
    String rawSecret;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM oauth2_authorization");
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenant = tenantProvisioningService.createTenant(SLUG, "Overround");
        app = applicationRepository.save(new Application(
            null, tenant.id(), "App", "Test app", LocalDateTime.now()
        ));
        admin = userRepository.save(new User(
            null, tenant.id(), "admin@example.test",
            passwordEncoder.encode(PASSWORD),
            true, false, true, true, LocalDateTime.now()
        ));
        alice = userRepository.save(new User(
            null, tenant.id(), END_USER_EMAIL,
            passwordEncoder.encode(PASSWORD),
            true, false, false, true, LocalDateTime.now()
        ));

        String internalClientId = UUID.randomUUID().toString();
        clientId = UUID.randomUUID().toString();
        rawSecret = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(internalClientId)
            .clientId(clientId)
            .clientSecret(passwordEncoder.encode(rawSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT_URI)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, internalClientId, app.id(), tenant.id(), "BFF Client", true
        ));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.id(), tenant.id(), alice.id(), admin.id(),
            internalClientId, Set.of()
        );
    }

    @Test
    @DisplayName("Token endpoint with a deliberately-broken JWK source returns 500 server_error JSON, not 403 [no body]")
    void runtimeFaultDuringTokenIssuanceProducesOAuth2ErrorJson() throws Exception {
        Pkce pkce = newPkce();
        BrowserSession browser = new BrowserSession(baseUrl());
        String code = browser.driveAuthorizeFlow(SLUG, clientId, REDIRECT_URI, pkce.challenge(),
            END_USER_EMAIL, PASSWORD);

        HttpResponse<String> token = browser.postToken(SLUG, code, pkce.verifier(), clientId, rawSecret);

        // Pre-fix this was `403` with empty body. Post-fix the filter writes
        // RFC 6749 §5.2 JSON with a real status code.
        assertThat(token.statusCode())
            .as("/oauth2/token must not leak Spring Security's empty-403 default")
            .isEqualTo(500);
        String contentType = token.headers().firstValue("content-type").orElse("");
        assertThat(contentType).startsWith("application/json");
        JsonNode body = objectMapper.readTree(token.body());
        assertThat(body.get("error").asText()).isEqualTo("server_error");
        assertThat(body.has("error_description")).isTrue();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private record Pkce(String verifier, String challenge) {}

    private static Pkce newPkce() throws Exception {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return new Pkce(verifier, challenge);
    }

    /**
     * Replaces the production {@link JWKSource} with one whose
     * {@link JWKSource#get} throws — modelling the deployed-image symptom
     * (tenant signing key fails to unwrap, e.g. KEK mismatch). Marked
     * {@link Primary} so it wins over the production bean.
     */
    @TestConfiguration
    static class BrokenJwkSourceConfig {
        @Bean
        @Primary
        JWKSource<SecurityContext> brokenJwkSource() {
            return new JWKSource<>() {
                @Override
                public List<JWK> get(JWKSelector selector, SecurityContext securityContext) {
                    throw new JwtEncodingException(
                        "Failed to select a JWK signing key -> Unable to invoke Cipher due to bad padding");
                }
            };
        }
    }

    private static final class BrowserSession {
        private static final Pattern CSRF_INPUT =
            Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

        private final String baseUrl;
        private final HttpClient http;

        BrowserSession(String baseUrl) {
            this.baseUrl = baseUrl;
            this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }

        String driveAuthorizeFlow(String slug, String clientId, String redirectUri,
                                  String codeChallenge, String email, String password) throws Exception {
            String authorizeUrl = baseUrl + "/t/" + slug + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=openid"
                + "&state=test-state"
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

            HttpResponse<String> r1 = get(authorizeUrl);
            requireStatus(r1, 302, "GET /authorize");
            String loginLocation = r1.headers().firstValue("location").orElseThrow();
            assertThat(loginLocation).contains("/t/" + slug + "/login");

            HttpResponse<String> r2 = get(absolute(loginLocation));
            requireStatus(r2, 200, "GET /login");
            Matcher m = CSRF_INPUT.matcher(r2.body());
            if (!m.find()) {
                throw new AssertionError("CSRF token not found on /login page");
            }
            String csrf = m.group(1);

            String form = "email=" + enc(email)
                + "&password=" + enc(password)
                + "&_csrf=" + enc(csrf);
            HttpResponse<String> r3 = post(baseUrl + "/t/" + slug + "/login",
                form, "application/x-www-form-urlencoded", null);
            requireStatus(r3, 302, "POST /login");
            String resume = r3.headers().firstValue("location").orElseThrow();
            assertThat(resume).contains("/oauth2/authorize");

            HttpResponse<String> r4 = get(absolute(resume));
            requireStatus(r4, 302, "GET resume");
            String callback = r4.headers().firstValue("location").orElseThrow();
            String code = queryParam(callback, "code");
            if (code == null || code.isBlank()) {
                throw new AssertionError("Did not receive code; resume redirected to: " + callback);
            }
            return code;
        }

        HttpResponse<String> postToken(String slug, String code, String verifier,
                                       String clientId, String clientSecret) throws Exception {
            String body = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_verifier=" + enc(verifier);
            String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            return post(baseUrl + "/t/" + slug + "/oauth2/token",
                body, "application/x-www-form-urlencoded", "Basic " + basic);
        }

        private String absolute(String location) {
            return location.startsWith("http") ? location : baseUrl + location;
        }

        private HttpResponse<String> get(String url) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> post(String url, String body, String contentType, String authHeader) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (authHeader != null) {
                b.header("Authorization", authHeader);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        private static void requireStatus(HttpResponse<String> r, int expected, String label) {
            if (r.statusCode() != expected) {
                throw new AssertionError(label + ": expected " + expected + " but got "
                    + r.statusCode() + " — Location=" + r.headers().firstValue("location").orElse("[none]")
                    + " body=[" + (r.body() == null ? "" : r.body().substring(0, Math.min(200, r.body().length()))) + "]");
            }
        }

        private static String queryParam(String url, String name) {
            int q = url.indexOf('?');
            if (q < 0) return null;
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                if (key.equals(name)) {
                    return eq >= 0 ? pair.substring(eq + 1) : "";
                }
            }
            return null;
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }
}

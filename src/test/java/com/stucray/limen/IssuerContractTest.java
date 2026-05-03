package com.stucray.limen;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OIDC issuer contract")
class IssuerContractTest {

    private static final String CLIENT_ID = "bff-client";
    private static final String CLIENT_SECRET = "test-secret";
    private static final String REDIRECT_URI = "http://localhost:8091/login/oauth2/code/bff-client";

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RegisteredClientRepository registeredClientRepository;

    @BeforeEach
    void setUp() {
        Long systemTenantId = tenantRepository.findBySlug("system").orElseThrow().id();
        if (!userRepository.existsByEmailAndTenantId("testuser@example.test", systemTenantId)) {
            userRepository.save(new User(null, systemTenantId, "testuser@example.test", passwordEncoder.encode("password"), true, false, false, true, LocalDateTime.now()));
        }

        RegisteredClient existing = registeredClientRepository.findByClientId(CLIENT_ID);
        if (existing == null) {
            registeredClientRepository.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret(passwordEncoder.encode(CLIENT_SECRET))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
                .build());
        }
    }

    @Test
    @DisplayName("/.well-known/openid-configuration advertises issuer, JWKS, authorize, and token endpoints")
    void openidConfigurationHasCorrectEndpoints() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost:8090"))
            .andExpect(jsonPath("$.jwks_uri").value("http://localhost:8090/oauth2/jwks"))
            .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost:8090/oauth2/authorize"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8090/oauth2/token"));
    }

    // Legacy global JWKS endpoint test removed in slice 5a (#18): the JWKSource bean is now
    // TenantJwkSource, which requires a tenant context (issuer or TenantScope). The global
    // /oauth2/jwks endpoint has no tenant context and intentionally fails — clients must use
    // /t/{slug}/.well-known/jwks.json. Tenant-scoped JWKS isolation is asserted in
    // TenantOAuth2RoutingIntegrationTest#tenantJwksEndpointsServeIsolatedKeys.

    // Legacy global authorization-code flow test removed: end-to-end token issuance is now
    // tenant-scoped (see TenantOAuth2RoutingIntegrationTest#authorizationCodePkceFlowProducesTokenWithTenantClaims).
    // Calling the global /oauth2/authorize without going through the /t/{slug}/ routing filter
    // intentionally fails because TenantAwareOAuth2AuthorizationService hard-fails on missing
    // TenantScope to surface filter-chain misconfigurations (parent PRD #13 user story 9).
}

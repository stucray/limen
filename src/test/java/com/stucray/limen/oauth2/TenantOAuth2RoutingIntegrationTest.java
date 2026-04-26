package com.stucray.limen.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.management.clients.ClientManagementService;
import com.stucray.limen.management.clients.ClientManagementService.ClientCreationResult;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"LIMEN_SIGNING_KEY_PATH=./target/test-signing-key.jwk"})
@AutoConfigureMockMvc
class TenantOAuth2RoutingIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    Tenant alphaCorpTenant;
    Tenant betaCorpTenant;
    Application alphaApp;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        alphaCorpTenant = tenantRepository.save(new Tenant(
            null, "alpha-corp", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        betaCorpTenant = tenantRepository.save(new Tenant(
            null, "beta-corp", "Beta Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        alphaApp = applicationRepository.save(new Application(
            null, alphaCorpTenant.id(), "Alpha App", "Test app", LocalDateTime.now()
        ));
    }

    @Test
    void discoveryDocumentHasTenantIssuer() throws Exception {
        mockMvc.perform(get("/t/alpha-corp/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost/t/alpha-corp"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost/t/alpha-corp/oauth2/token"))
            .andExpect(jsonPath("$.jwks_uri").value("http://localhost/t/alpha-corp/oauth2/jwks"))
            .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost/t/alpha-corp/oauth2/authorize"));
    }

    @Test
    void unknownTenantSlugReturns404() throws Exception {
        mockMvc.perform(get("/t/no-such-tenant/.well-known/openid-configuration"))
            .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantReturns403() throws Exception {
        tenantRepository.save(new Tenant(
            null, "suspended-co", "Suspended Co", TenantStatus.SUSPENDED, LocalDateTime.now()
        ));

        mockMvc.perform(get("/t/suspended-co/.well-known/openid-configuration"))
            .andExpect(status().isForbidden());
    }

    @Test
    void clientCredentialsTokenFlowSucceedsForTenantClient() throws Exception {
        ClientCreationResult result = clientManagementService.createClient(
            alphaApp.id(), alphaCorpTenant.id(),
            "m2m-client",
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, true
        );

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
    void crossTenantClientRejected() throws Exception {
        // Register a client under alpha-corp
        ClientCreationResult result = clientManagementService.createClient(
            alphaApp.id(), alphaCorpTenant.id(),
            "alpha-m2m",
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, true
        );

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
}

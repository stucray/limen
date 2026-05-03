package com.stucray.limen.management.clients;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.tenant.Tenant;
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
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OAuth clients: CRUD on the management surface")
class ClientManagementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    MockHttpSession sessionA;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM client_metadata");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenantA = tenantRepository.save(new Tenant(null, "corp-a", "Corp A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "corp-b", "Corp B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
        userRepository.save(new User(null, tenantA.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp-a/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    private String clientsUrl() {
        return "/manage/t/corp-a/applications/" + appA.id() + "/clients";
    }

    private TenantClient createConfidentialClient(String name) {
        return clientManagementService.createClient(
            appA.id(), tenantA.id(), name,
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, true, 5, 30, false
        ).client();
    }

    @Test
    @DisplayName("Owner can list the application's OAuth clients")
    void ownerCanListClients() throws Exception {
        createConfidentialClient("Visible Client");

        mockMvc.perform(get(clientsUrl()).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Visible Client")));
    }

    @Test
    @DisplayName("Owner can GET the new-client form")
    void ownerCanGetNewClientForm() throws Exception {
        mockMvc.perform(get(clientsUrl() + "/new").session(sessionA))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Creating a confidential client flashes the one-time clientSecret + clientId for the owner to copy")
    void ownerCanCreateConfidentialClient() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "Created Client")
                .param("grantTypes", "authorization_code", "refresh_token")
                .param("redirectUris", "http://localhost/cb1\nhttp://localhost/cb2")
                .param("postLogoutRedirectUris", "")
                .param("scopes", "openid,profile")
                .param("requirePkce", "false")
                .param("confidential", "true")
                .param("accessTokenTtlMinutes", "10")
                .param("refreshTokenTtlDays", "7")
                .param("reuseRefreshTokens", "false"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()))
            .andExpect(flash().attributeExists("clientSecret"))
            .andExpect(flash().attributeExists("clientId"));

        assertThat(tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id()))
            .extracting(TenantClient::displayName).containsExactly("Created Client");
    }

    @Test
    @DisplayName("Public-client creation does not flash a clientSecret (PKCE-only flow has no secret)")
    void publicClientCreationDoesNotProduceSecretFlash() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "Public Client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid")
                .param("confidential", "false"))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("clientSecret", org.hamcrest.Matchers.nullValue()))
            .andExpect(flash().attribute("clientId", org.hamcrest.Matchers.nullValue()));

        assertThat(tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id()))
            .extracting(TenantClient::confidential).containsExactly(false);
    }

    @Test
    @DisplayName("Owner can GET the edit-client form prefilled with the existing client's display name")
    void ownerCanGetEditClientForm() throws Exception {
        TenantClient created = createConfidentialClient("Editable Client");

        mockMvc.perform(get(clientsUrl() + "/" + created.registeredClientId() + "/edit").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Editable Client")));
    }

    @Test
    @DisplayName("POST /{clientId}/edit updates token TTLs, refresh-reuse, and PKCE flag on the registered client")
    void ownerCanUpdateClientSettings() throws Exception {
        TenantClient created = createConfidentialClient("Updatable Client");

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("accessTokenTtlMinutes", "15")
                .param("refreshTokenTtlDays", "60")
                .param("reuseRefreshTokens", "true")
                .param("requirePkce", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()));

        RegisteredClient updated = registeredClientRepository.findById(created.registeredClientId());
        assertThat(updated.getTokenSettings().getAccessTokenTimeToLive().toMinutes()).isEqualTo(15);
        assertThat(updated.getTokenSettings().getRefreshTokenTimeToLive().toDays()).isEqualTo(60);
        assertThat(updated.getTokenSettings().isReuseRefreshTokens()).isTrue();
        assertThat(updated.getClientSettings().isRequireProofKey()).isTrue();
    }

    @Test
    @DisplayName("POST /{clientId}/rotate-secret replaces the stored hash and flashes the new secret once")
    void ownerCanRotateClientSecret() throws Exception {
        TenantClient created = createConfidentialClient("Rotating Client");
        String oldHash = registeredClientRepository.findById(created.registeredClientId()).getClientSecret();

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/rotate-secret")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()))
            .andExpect(flash().attributeExists("clientSecret"))
            .andExpect(flash().attribute("clientId", created.registeredClientId()));

        String newHash = registeredClientRepository.findById(created.registeredClientId()).getClientSecret();
        assertThat(newHash).isNotEqualTo(oldHash);
    }

    @Test
    @DisplayName("POST /{clientId}/delete removes both the tenant_client row and the registered_client row")
    void ownerCanDeleteClient() throws Exception {
        TenantClient created = createConfidentialClient("Deletable Client");

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/delete")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()));

        assertThat(tenantClientRepository.findByRegisteredClientId(created.registeredClientId())).isEmpty();
        assertThat(registeredClientRepository.findById(created.registeredClientId())).isNull();
    }

    @Test
    @DisplayName("Tenant A's clients list is not visible to a tenant B session — TenantAccessFilter forces logout")
    void tenantAClientsNotVisibleToTenantBSession() throws Exception {
        createConfidentialClient("Tenant A Client");
        userRepository.save(new User(null, tenantB.id(), "ownerB@example.test", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/corp-b/login")
                .param("email", "ownerB@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        mockMvc.perform(get(clientsUrl()).session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp-a/login"));
    }
}

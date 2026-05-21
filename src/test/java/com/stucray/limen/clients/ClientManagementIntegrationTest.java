package com.stucray.limen.clients;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        userRepository.save(new User(null, tenantA.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp-a/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    private String clientsUrl() {
        return "/manage/t/corp-a/applications/" + appA.id() + "/clients";
    }

    private TenantClient createConfidentialClient(String name) {
        return clientManagementService.createClient(new CreateClientCommand(
            appA.id(), tenantA.id(), name,
            Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS),
            Set.of(), Set.of(), Set.of("read"),
            false, false, true, 5, 30, false
        )).client();
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
    @DisplayName("Creating a confidential client flashes the one-time clientSecret + the wire client_id (not the internal SAS PK)")
    void ownerCanCreateConfidentialClient() throws Exception {
        MvcResult result = mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
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
            .andExpect(flash().attributeExists("clientId"))
            .andReturn();

        TenantClient persisted = tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id())
            .stream().findFirst().orElseThrow();
        RegisteredClient registered = registeredClientRepository.findById(persisted.registeredClientId());
        String flashedClientId = (String) result.getFlashMap().get("clientId");
        assertThat(flashedClientId).isEqualTo(registered.getClientId());
        assertThat(flashedClientId).isNotEqualTo(persisted.registeredClientId());
        assertThat(persisted.displayName()).isEqualTo("Created Client");
    }

    @Test
    @DisplayName("Creating a client without requireConsent persists requireAuthorizationConsent=false (default-off; #273)")
    void newClientDefaultsToConsentOff() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "No-Consent Client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid")
                .param("confidential", "true"))
            .andExpect(status().is3xxRedirection());

        TenantClient persisted = tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id())
            .stream().findFirst().orElseThrow();
        RegisteredClient registered = registeredClientRepository.findById(persisted.registeredClientId());
        assertThat(registered.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    @DisplayName("Creating a client with requireConsent checked persists requireAuthorizationConsent=true (opt-in works)")
    void newClientWithConsentCheckedPersistsConsentTrue() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "Consent Client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid")
                .param("confidential", "true")
                .param("requireConsent", "true"))
            .andExpect(status().is3xxRedirection());

        TenantClient persisted = tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id())
            .stream().findFirst().orElseThrow();
        RegisteredClient registered = registeredClientRepository.findById(persisted.registeredClientId());
        assertThat(registered.getClientSettings().isRequireAuthorizationConsent()).isTrue();
    }

    @Test
    @DisplayName("POST /{clientId}/edit can flip requireAuthorizationConsent on existing clients (escape hatch for clients created before #273 fix)")
    void ownerCanFlipConsentOnExistingClient() throws Exception {
        TenantClient created = createConfidentialClient("Flip-Consent Client");

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "client_credentials")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30")
                .param("requireConsent", "true"))
            .andExpect(status().is3xxRedirection());

        RegisteredClient afterOn = registeredClientRepository.findById(created.registeredClientId());
        assertThat(afterOn.getClientSettings().isRequireAuthorizationConsent()).isTrue();

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "client_credentials")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30"))
            .andExpect(status().is3xxRedirection());

        RegisteredClient afterOff = registeredClientRepository.findById(created.registeredClientId());
        assertThat(afterOff.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    @DisplayName("Scopes input accepts whitespace-separated values (label-parser parity fix for #274)")
    void scopesInputAcceptsWhitespaceSeparated() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "Whitespace Scopes Client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid profile email")
                .param("confidential", "true"))
            .andExpect(status().is3xxRedirection());

        TenantClient persisted = tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id())
            .stream().findFirst().orElseThrow();
        RegisteredClient registered = registeredClientRepository.findById(persisted.registeredClientId());
        assertThat(registered.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    @DisplayName("Scopes input also accepts comma- and newline-separated values (back-compat)")
    void scopesInputAcceptsCommaAndNewlineSeparated() throws Exception {
        mockMvc.perform(post(clientsUrl()).session(sessionA).with(csrf())
                .param("displayName", "Mixed Scopes Client")
                .param("grantTypes", "authorization_code")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid,profile\nemail")
                .param("confidential", "true"))
            .andExpect(status().is3xxRedirection());

        TenantClient persisted = tenantClientRepository.findAllByApplicationIdAndTenantId(appA.id(), tenantA.id())
            .stream().findFirst().orElseThrow();
        RegisteredClient registered = registeredClientRepository.findById(persisted.registeredClientId());
        assertThat(registered.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    @DisplayName("authorization_code grant with empty scopes is rejected loudly, not silently stored (#274)")
    void emptyScopesWithAuthorizationCodeGrantIsRejected() {
        assertThatThrownBy(() -> clientManagementService.createClient(new CreateClientCommand(
            appA.id(), tenantA.id(), "Empty Scopes Client",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/cb"), Set.of(), Set.of(),
            false, false, true, 5, 30, false
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Scopes are required for authorization_code grant");
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
    @DisplayName("GET edit form warns when a client has zero Client Members — /oauth2/authorize would deny all sign-ins (#309 diagnostic)")
    void editFormWarnsWhenClientHasNoMembers() throws Exception {
        TenantClient created = createConfidentialClient("Membership-less Client");

        mockMvc.perform(get(clientsUrl() + "/" + created.registeredClientId() + "/edit").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No client members granted")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("client-no-members-warning")));
    }

    @Test
    @DisplayName("GET edit form lists granted client-member emails so an operator can confirm membership state at a glance (#309 diagnostic)")
    void editFormListsGrantedClientMemberEmails() throws Exception {
        TenantClient created = createConfidentialClient("With-Members Client");
        Long ownerId = userRepository.findByEmailAndTenantId("owner@example.test", tenantA.id()).orElseThrow().id();
        TenantClient tcRow = tenantClientRepository.findByRegisteredClientIdAndTenantId(
            created.registeredClientId(), tenantA.id()).orElseThrow();

        Long appMembershipId = jdbcTemplate.queryForObject(
            "INSERT INTO application_membership (user_id, application_id, granted_at, granted_by) " +
                "VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, ownerId, appA.id(), LocalDateTime.now(), ownerId
        );
        jdbcTemplate.update(
            "INSERT INTO client_membership (user_id, client_metadata_id, application_membership_id, granted_at, granted_by) " +
                "VALUES (?, ?, ?, ?, ?)",
            ownerId, tcRow.id(), appMembershipId, LocalDateTime.now(), ownerId
        );

        mockMvc.perform(get(clientsUrl() + "/" + created.registeredClientId() + "/edit").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("owner@example.test")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("client-members-summary")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("client-no-members-warning"))));
    }

    private TenantClient createAuthorizationCodeClient(String name, Set<String> redirectUris, Set<String> scopes) {
        return clientManagementService.createClient(new CreateClientCommand(
            appA.id(), tenantA.id(), name,
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            redirectUris, Set.of(), scopes,
            false, false, true, 5, 30, false
        )).client();
    }

    @Test
    @DisplayName("GET edit form pre-populates existing redirect URIs, post-logout URIs, and scopes (so a TTL bump doesn't silently wipe auth config)")
    void editFormPrePopulatesAuthorizationSettings() throws Exception {
        TenantClient created = createAuthorizationCodeClient(
            "Prepopulated Client",
            Set.of("http://localhost/cb1"),
            Set.of("openid", "profile")
        );

        mockMvc.perform(get(clientsUrl() + "/" + created.registeredClientId() + "/edit").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("http://localhost/cb1")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("openid")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("profile")));
    }

    @Test
    @DisplayName("POST edit persists added redirect URIs (issue #306 — round-trip on the trigger use case)")
    void ownerCanAddRedirectUriOnExistingClient() throws Exception {
        TenantClient created = createAuthorizationCodeClient(
            "Editable AC Client",
            Set.of("http://localhost:8091/cb"),
            Set.of("openid")
        );

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "authorization_code")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30")
                .param("redirectUris", "http://localhost:8091/cb\nhttp://localhost:4200/cb")
                .param("scopes", "openid"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()));

        RegisteredClient updated = registeredClientRepository.findById(created.registeredClientId());
        assertThat(updated.getRedirectUris()).containsExactlyInAnyOrder(
            "http://localhost:8091/cb", "http://localhost:4200/cb");
    }

    @Test
    @DisplayName("POST /{clientId}/edit adds refresh_token grant to an authorization_code client created without it (the canonical fix path from #308 — silent-broken BFF use case)")
    void ownerCanAddRefreshTokenGrantOnExistingClient() throws Exception {
        TenantClient created = createAuthorizationCodeClient(
            "Refresh-less Client", Set.of("http://localhost/cb"), Set.of("openid"));
        assertThat(registeredClientRepository.findById(created.registeredClientId()).getAuthorizationGrantTypes())
            .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "authorization_code", "refresh_token")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()));

        RegisteredClient updated = registeredClientRepository.findById(created.registeredClientId());
        assertThat(updated.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    }

    @Test
    @DisplayName("GET edit form pre-checks the client's current authorization_grant_types (so an operator can audit at a glance — #308)")
    void editFormPreChecksCurrentGrantTypes() throws Exception {
        TenantClient created = createAuthorizationCodeClient(
            "Grant-prefill Client", Set.of("http://localhost/cb"), Set.of("openid"));

        MvcResult result = mockMvc.perform(get(clientsUrl() + "/" + created.registeredClientId() + "/edit").session(sessionA))
            .andExpect(status().isOk())
            .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).containsPattern("value=\"authorization_code\"[^>]*checked");
        assertThat(body).doesNotContainPattern("value=\"refresh_token\"[^>]*checked");
        assertThat(body).doesNotContainPattern("value=\"client_credentials\"[^>]*checked");
    }

    @Test
    @DisplayName("POST edit persists changed scopes (round-trip on scopes)")
    void ownerCanUpdateScopesOnExistingClient() throws Exception {
        TenantClient created = createAuthorizationCodeClient(
            "Scope-edit Client",
            Set.of("http://localhost/cb"),
            Set.of("openid")
        );

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "authorization_code")
                .param("accessTokenTtlMinutes", "5")
                .param("refreshTokenTtlDays", "30")
                .param("redirectUris", "http://localhost/cb")
                .param("scopes", "openid profile email"))
            .andExpect(status().is3xxRedirection());

        RegisteredClient updated = registeredClientRepository.findById(created.registeredClientId());
        assertThat(updated.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    @DisplayName("Removing the last redirect URI on an authorization_code client is rejected loudly, not silently stored")
    void updateRejectsEmptyRedirectsOnAuthorizationCodeClient() {
        TenantClient created = createAuthorizationCodeClient(
            "AC Client", Set.of("http://localhost/cb"), Set.of("openid"));

        assertThatThrownBy(() -> clientManagementService.updateClientSettings(new UpdateClientCommand(
            created.registeredClientId(), tenantA.id(),
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of(), Set.of(), Set.of("openid"),
            false, false, 5, 30, false
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("At least one redirect URI is required for authorization_code grant");
    }

    @Test
    @DisplayName("Creating an authorization_code client with zero redirect URIs is rejected (parity with the update-path rule)")
    void createRejectsEmptyRedirectsOnAuthorizationCodeClient() {
        assertThatThrownBy(() -> clientManagementService.createClient(new CreateClientCommand(
            appA.id(), tenantA.id(), "No-Redirect Client",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of(), Set.of(), Set.of("openid"),
            false, false, true, 5, 30, false
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("At least one redirect URI is required for authorization_code grant");
    }

    @Test
    @DisplayName("POST /{clientId}/edit updates token TTLs, refresh-reuse, and PKCE flag on the registered client")
    void ownerCanUpdateClientSettings() throws Exception {
        TenantClient created = createConfidentialClient("Updatable Client");

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/edit")
                .session(sessionA).with(csrf())
                .param("grantTypes", "client_credentials")
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
    @DisplayName("POST /{clientId}/rotate-secret replaces the stored hash and flashes the new secret + wire client_id")
    void ownerCanRotateClientSecret() throws Exception {
        TenantClient created = createConfidentialClient("Rotating Client");
        RegisteredClient registered = registeredClientRepository.findById(created.registeredClientId());
        String oldHash = registered.getClientSecret();

        mockMvc.perform(post(clientsUrl() + "/" + created.registeredClientId() + "/rotate-secret")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(clientsUrl()))
            .andExpect(flash().attributeExists("clientSecret"))
            .andExpect(flash().attribute("clientId", registered.getClientId()));

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
        userRepository.save(new User(null, tenantB.id(), "ownerB@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/corp-b/login")
                .param("email", "ownerB@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        mockMvc.perform(get(clientsUrl()).session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp-a/login"));
    }
}

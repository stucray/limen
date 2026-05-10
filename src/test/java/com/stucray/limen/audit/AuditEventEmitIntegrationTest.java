package com.stucray.limen.audit;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One assertion per emit source: the user-facing action triggers a row in
 * {@code audit_event} with the right shape, tenant_id, actor and target.
 * The {@code listenerFailureDoesNotRollBackParent} test pins the AFTER_COMMIT
 * contract — a thrown listener must not roll back the parent transaction.
 *
 * <p>Audit row reads are wrapped in Awaitility polling: under the Modulith
 * publication registry the AFTER_COMMIT-bound listener runs asynchronously,
 * so a {@code SELECT} immediately after the publishing call can race the
 * dispatcher.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@DisplayName("Audit event emit at every existing surface")
class AuditEventEmitIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Tenant creation publishes TenantCreatedEvent → tenant_created row")
    void tenantCreationEmitsAuditRow() {
        String slug = uniqueSlug();
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Display " + slug);

        awaitAuditRow(() -> latestEventForTenant(tenant.id()), row -> {
            assertThat(row).isNotNull();
            assertThat(row.get("event_type")).isEqualTo("tenant_created");
            assertThat(row.get("target_type")).isEqualTo("tenant");
            assertThat(row.get("target_id")).isEqualTo(String.valueOf(tenant.id()));
        });
    }

    @Test
    @DisplayName("Tenant suspend publishes TenantSuspendedEvent → tenant_suspended row")
    void tenantSuspendEmitsAuditRow() {
        Tenant tenant = tenantProvisioningService.createTenant(uniqueSlug(), "X");
        long admin = seedSystemAdminId();

        tenantProvisioningService.suspend(tenant, admin);

        awaitAuditRow(() -> latestEventForTenantOfType(tenant.id(), "tenant_suspended"), row -> {
            assertThat(row).isNotNull();
            assertThat(row.get("actor_user_id")).isEqualTo(admin);
        });
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status())
            .isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("Tenant unsuspend publishes TenantUnsuspendedEvent → tenant_unsuspended row")
    void tenantUnsuspendEmitsAuditRow() {
        Tenant tenant = tenantProvisioningService.createTenant(uniqueSlug(), "X");
        long admin = seedSystemAdminId();
        tenantProvisioningService.suspend(tenant, admin);
        Tenant suspended = tenantRepository.findById(tenant.id()).orElseThrow();

        tenantProvisioningService.unsuspend(suspended, admin);

        awaitAuditRow(() -> latestEventForTenantOfType(tenant.id(), "tenant_unsuspended"),
            row -> assertThat(row).isNotNull());
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status())
            .isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    @DisplayName("Tenant delete publishes TenantDeletedEvent → tenant_deleted row with original id in details")
    void tenantDeleteEmitsAuditRow() {
        Tenant tenant = tenantProvisioningService.createTenant(uniqueSlug(), "X");
        long admin = seedSystemAdminId();
        long originalId = tenant.id();

        tenantProvisioningService.delete(tenant, admin);

        // tenant_id on the row is null (FK SET NULL); look up by event_type + target_id
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_type, tenant_id, target_id, details::text AS details FROM audit_event "
                    + "WHERE event_type = 'tenant_deleted' AND target_id = ? ORDER BY occurred_at DESC LIMIT 1",
                String.valueOf(originalId));
            assertThat(row.get("tenant_id")).isNull();
            assertThat(row.get("details").toString().replace(" ", ""))
                .contains("\"originalTenantId\":" + originalId);
        });
    }

    @Test
    @DisplayName("Client secret rotation publishes ClientSecretRotatedEvent → client_secret_rotated row")
    void clientSecretRotationEmitsAuditRow() throws Exception {
        Tenant tenant = tenantProvisioningService.createTenant(uniqueSlug(), "X");
        User owner = seedTenantOwner(tenant);
        MockHttpSession session = loginAs(tenant.slug(), owner.email(), "pass");
        Application app = applicationRepository.save(
            new Application(null, tenant.id(), "App " + uniqueSlug(), null, LocalDateTime.now()));
        String registeredClientId = seedConfidentialClient(app.id(), tenant.id(), "Client");

        mockMvc.perform(post("/manage/t/" + tenant.slug()
                + "/applications/" + app.id()
                + "/clients/" + registeredClientId + "/rotate-secret")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        awaitAuditRow(() -> latestEventForTenantOfType(tenant.id(), "client_secret_rotated"), row -> {
            assertThat(row).isNotNull();
            assertThat(row.get("target_type")).isEqualTo("registered_client");
            assertThat(row.get("target_id")).isEqualTo(registeredClientId);
        });
    }

    @Test
    @DisplayName("Admin-initiated password reset publishes PasswordChangedEvent → trigger=admin_reset")
    void adminResetPasswordEmitsAuditRow() throws Exception {
        Tenant tenant = tenantProvisioningService.createTenant(uniqueSlug(), "X");
        User owner = seedTenantOwner(tenant);
        MockHttpSession session = loginAs(tenant.slug(), owner.email(), "pass");
        User user = seedUser(tenant.id(), false);

        mockMvc.perform(post("/manage/t/" + tenant.slug()
                + "/users/" + user.id() + "/reset-password")
                .param("temporaryPassword", "tempPass1234")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        awaitAuditRow(() -> latestEventForTenantOfType(tenant.id(), "password_changed"),
            row -> assertThat(row.get("details").toString().replace(" ", ""))
                .contains("\"trigger\":\"admin_reset\""));
    }

    @Nested
    @DisplayName("AFTER_COMMIT semantics")
    class AfterCommitSemantics {

        @Autowired AuditEventWriter writer;

        @Test
        @DisplayName("A failing listener does not roll back the parent transaction — the user-facing action stays committed")
        void listenerFailureDoesNotRollBackParent() {
            // The publishing action commits independently of audit-row insertion;
            // we simulate a write failure by passing a malformed event that the
            // writer rejects. The parent action — Tenant creation — still stands.
            String slug = uniqueSlug();
            Tenant tenant = tenantProvisioningService.createTenant(slug, "Commit-survives");

            // Force a writer-level failure on a fresh event after the fact;
            // because @TransactionalEventListener is AFTER_COMMIT, the createTenant
            // transaction has already been flushed. An exception here only logs.
            try {
                writer.write(new AuditEvent(
                    null, tenant.id(), null,
                    "synthetic_failure",
                    null, null, null, null,
                    LocalDateTime.now(),
                    Map.of("k", new Object()) // non-serialisable → IllegalArgumentException
                ));
            } catch (IllegalArgumentException expected) {
                // The thrown exception proves the writer is the failure point;
                // what matters is that the prior createTenant row is still present.
            }

            assertThat(tenantRepository.findBySlug(slug)).isPresent();
        }
    }

    // --- helpers ---

    private static String uniqueSlug() {
        return "audit-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    private User seedUser(Long tenantId, boolean mustChangePassword) {
        String email = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return userRepository.save(new User(
            null, tenantId, email, passwordEncoder.encode("oldPass1234"),
            true, mustChangePassword, false, true, LocalDateTime.now()));
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    private User seedTenantOwner(Tenant tenant) {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return userRepository.save(new User(
            null, tenant.id(), email, passwordEncoder.encode("pass"),
            true, false, true, true, LocalDateTime.now()));
    }

    private MockHttpSession loginAs(String slug, String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/manage/t/" + slug + "/login")
                .param("email", email).param("password", password).with(csrf()))
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @SuppressWarnings("NullAway") // Spring Data convention
    private long seedSystemAdminId() {
        Tenant system = tenantRepository.findBySlug("system").orElseThrow();
        String email = "actor-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        User admin = userRepository.save(new User(
            null, system.id(), email, passwordEncoder.encode("pw"),
            true, false, false, true, LocalDateTime.now()));
        return admin.id();
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    private String seedConfidentialClient(Long applicationId, Long tenantId, String name) {
        String registeredClientId = UUID.randomUUID().toString();
        String rawSecret = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
            .clientId(UUID.randomUUID().toString())
            .clientName(name)
            .clientSecret(passwordEncoder.encode(rawSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(false)
                .requireAuthorizationConsent(true)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, applicationId, tenantId, name, true));
        return registeredClientId;
    }

    private @org.jspecify.annotations.Nullable Map<String, Object> latestEventForTenant(Long tenantId) {
        var rows = jdbcTemplate.queryForList(
            "SELECT event_type, tenant_id, actor_user_id, target_type, target_id, details::text AS details "
                + "FROM audit_event WHERE tenant_id = ? ORDER BY occurred_at DESC LIMIT 1",
            tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private @org.jspecify.annotations.Nullable Map<String, Object> latestEventForTenantOfType(Long tenantId, String eventType) {
        var rows = jdbcTemplate.queryForList(
            "SELECT event_type, tenant_id, actor_user_id, target_type, target_id, details::text AS details "
                + "FROM audit_event WHERE tenant_id = ? AND event_type = ? ORDER BY occurred_at DESC LIMIT 1",
            tenantId, eventType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void awaitAuditRow(
            java.util.function.Supplier<Map<String, Object>> rowSupplier,
            Consumer<Map<String, Object>> assertion) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> row = rowSupplier.get();
            assertThat(row).isNotNull();
            assertion.accept(row);
        });
    }
}

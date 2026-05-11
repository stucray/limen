package com.stucray.limen.oauth2.sas;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("TenantAwareOAuth2AuthorizationConsentService: TenantScope-required save/find/remove with cross-tenant isolation")
class TenantAwareOAuth2AuthorizationConsentServiceIntegrationTest {

    @Autowired OAuth2AuthorizationConsentService consentService;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;
    RegisteredClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_authorization_consent");
        jdbcTemplate.execute("DELETE FROM client_metadata");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('consent-alpha', 'consent-beta')");

        alpha = tenantRepository.save(new Tenant(
            null, "consent-alpha", "Consent Alpha", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        beta = tenantRepository.save(new Tenant(
            null, "consent-beta", "Consent Beta", TenantStatus.ACTIVE, LocalDateTime.now()
        ));

        Application alphaApp = applicationRepository.save(new Application(
            null, alpha.id(), "Alpha App", "Test app", LocalDateTime.now()
        ));

        String internalId = UUID.randomUUID().toString();
        client = RegisteredClient.withId(internalId)
            .clientId(UUID.randomUUID().toString())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope("openid")
            .build();
        registeredClientRepository.save(client);
        // Map client to alpha so reads under alpha context resolve via the row mapper.
        // Cross-tenant reads in beta intentionally don't need a mapping — the tenant
        // filter eliminates rows before the row mapper runs.
        tenantClientRepository.save(new TenantClient(
            null, internalId, alphaApp.id(), alpha.id(), "Alpha Client", false
        ));
    }

    @Test
    @DisplayName("save() without an active TenantScope throws IllegalStateException — refuses to write a tenantless row")
    void saveWithoutTenantScopeThrows() {
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
            .withId(client.getId(), "alice")
            .authority(new SimpleGrantedAuthority("SCOPE_openid"))
            .build();

        assertThatThrownBy(() -> consentService.save(consent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("findById() without an active TenantScope throws IllegalStateException — refuses to read across tenants")
    void findByIdWithoutTenantScopeThrows() {
        assertThatThrownBy(() -> consentService.findById(client.getId(), "alice"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("remove() without an active TenantScope throws IllegalStateException — refuses to delete a tenantless row")
    void removeWithoutTenantScopeThrows() {
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
            .withId(client.getId(), "alice")
            .authority(new SimpleGrantedAuthority("SCOPE_openid"))
            .build();

        assertThatThrownBy(() -> consentService.remove(consent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("A consent saved under tenant alpha is invisible to a findById() call running under tenant beta's scope")
    void crossTenantFindByIdReturnsNull() {
        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
                .withId(client.getId(), "alice")
                .authority(new SimpleGrantedAuthority("SCOPE_openid"))
                .build();
            consentService.save(consent);
            assertThat(consentService.findById(client.getId(), "alice")).isNotNull();
        });

        TenantScope.run(beta.slug(), beta.id(), () -> {
            assertThat(consentService.findById(client.getId(), "alice")).isNull();
        });
    }

    @Test
    @DisplayName("save() under TenantScope persists the calling tenant's id into the tenant_id column")
    void savePersistsTenantIdColumn() {
        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
                .withId(client.getId(), "alice")
                .authority(new SimpleGrantedAuthority("SCOPE_openid"))
                .build();
            consentService.save(consent);
        });

        Long persistedTenantId = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM oauth2_authorization_consent "
                + "WHERE registered_client_id = ? AND principal_name = ?",
            Long.class, client.getId(), "alice"
        );
        assertThat(persistedTenantId).isEqualTo(alpha.id());
    }

    @Test
    @DisplayName("save() with the same (clientId, principal) within a tenant overwrites authorities — exactly one row remains")
    void updateOverwritesAuthoritiesWithinTenant() {
        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            consentService.save(OAuth2AuthorizationConsent
                .withId(client.getId(), "alice")
                .authority(new SimpleGrantedAuthority("SCOPE_openid"))
                .build());
            consentService.save(OAuth2AuthorizationConsent
                .withId(client.getId(), "alice")
                .authority(new SimpleGrantedAuthority("SCOPE_openid"))
                .authority(new SimpleGrantedAuthority("SCOPE_profile"))
                .build());

            OAuth2AuthorizationConsent consent = consentService.findById(client.getId(), "alice");
            assertThat(consent).isNotNull();
            assertThat(consent.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile");
        });

        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_authorization_consent "
                + "WHERE tenant_id = ? AND registered_client_id = ? AND principal_name = ?",
            Integer.class, alpha.id(), client.getId(), "alice"
        );
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("remove() under tenant alpha deletes only alpha's consent row — a manually-planted beta row with the same (clientId, principal) survives")
    void removeDeletesOnlyCallingTenantsRow() {
        OAuth2AuthorizationConsent alphaConsent = OAuth2AuthorizationConsent
            .withId(client.getId(), "alice")
            .authority(new SimpleGrantedAuthority("SCOPE_openid"))
            .build();

        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            consentService.save(alphaConsent);
        });

        // Plant a row directly for tenant beta to verify remove is tenant-scoped.
        // Bypassing the service avoids needing a beta client_metadata mapping (which
        // the UNIQUE constraint on registered_client_id wouldn't allow).
        jdbcTemplate.update(
            "INSERT INTO oauth2_authorization_consent (tenant_id, registered_client_id, principal_name, authorities) "
                + "VALUES (?, ?, ?, ?)",
            beta.id(), client.getId(), "alice", "SCOPE_openid"
        );

        // Remove under alpha — beta's row should survive.
        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            consentService.remove(alphaConsent);
        });

        Integer alphaRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE tenant_id = ?",
            Integer.class, alpha.id()
        );
        Integer betaRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE tenant_id = ?",
            Integer.class, beta.id()
        );
        assertThat(alphaRows).isZero();
        assertThat(betaRows).isEqualTo(1);
    }
}

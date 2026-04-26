package com.stucray.limen.tenant;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.security.SigningKeyStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"LIMEN_SIGNING_KEY_PATH=./target/test-signing-key.jwk"})
class TenantProvisioningServiceIntegrationTest {

    @Autowired TenantProvisioningService service;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean SigningKeyStore signingKeyStore;

    @Test
    void createTenantPersistsTenantAndActiveSigningKey() {
        String slug = "acme-" + System.nanoTime();

        Tenant tenant = service.createTenant(slug, "Acme Corp");

        assertThat(tenant.id()).isNotNull();
        assertThat(tenant.slug()).isEqualTo(slug);
        assertThat(tenant.displayName()).isEqualTo("Acme Corp");
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);

        Long activeKeys = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            Long.class,
            tenant.id()
        );
        assertThat(activeKeys).isOne();
    }

    @Test
    void createTenantSkipsSigningKeyForSystemSlug() {
        tenantRepository.findBySlug("system").ifPresent(t -> tenantRepository.deleteById(t.id()));

        Tenant systemTenant = service.createTenant("system", "System");

        assertThat(systemTenant.slug()).isEqualTo("system");
        Long keyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ?",
            Long.class,
            systemTenant.id()
        );
        assertThat(keyCount).isZero();
    }

    @Test
    void signingKeyFailureRollsBackTenantInsert() {
        doThrow(new IllegalStateException("simulated key store failure"))
            .when(signingKeyStore).createForTenant(anyLong());

        String slug = "rollback-" + System.nanoTime();

        assertThatThrownBy(() -> service.createTenant(slug, "Rollback Corp"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(tenantRepository.findBySlug(slug)).isEmpty();
    }

    @Test
    void deleteTenantCascadesToSigningKeys() {
        String slug = "deleteme-" + System.nanoTime();
        Tenant tenant = service.createTenant(slug, "Delete Me");
        long tenantId = tenant.id();

        service.deleteTenant(tenantId);

        assertThat(tenantRepository.findById(tenantId)).isEmpty();
        Long keyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ?",
            Long.class,
            tenantId
        );
        assertThat(keyCount).isZero();
    }
}

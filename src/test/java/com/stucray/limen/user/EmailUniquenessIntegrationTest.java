package com.stucray.limen.user;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.users.UserManagementService;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the per-tenant email uniqueness contract (PRD #120, slice #122):
 * the same email may identify two distinct Users in two different Tenants,
 * but a duplicate within a single Tenant is rejected.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("Email is unique per tenant, not globally — same email permitted across tenants, rejected within one tenant")
class EmailUniquenessIntegrationTest {

    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired UserManagementService userManagementService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenantA = tenantProvisioningService.createTenant("uniq-tenant-a", "Uniqueness Tenant A");
        tenantB = tenantProvisioningService.createTenant("uniq-tenant-b", "Uniqueness Tenant B");
    }

    @Test
    @DisplayName("Same email in two different tenants is permitted — both users persist independently")
    void sameEmailAcrossDifferentTenantsIsPermitted() {
        userManagementService.createUser(tenantA.id(), "shared@example.test", "temp-pwd-1");
        userManagementService.createUser(tenantB.id(), "shared@example.test", "temp-pwd-2");

        assertThat(userRepository.findByEmailAndTenantId("shared@example.test", tenantA.id())).isPresent();
        assertThat(userRepository.findByEmailAndTenantId("shared@example.test", tenantB.id())).isPresent();
        // Defence-in-depth: the two rows are distinct Users with distinct ids.
        assertThat(userRepository.findByEmailAndTenantId("shared@example.test", tenantA.id()).orElseThrow().id())
            .isNotEqualTo(userRepository.findByEmailAndTenantId("shared@example.test", tenantB.id()).orElseThrow().id());
    }

    @Test
    @DisplayName("Duplicate email within a single tenant is rejected with 'Email already exists in this tenant'")
    void duplicateEmailWithinSingleTenantIsRejected() {
        userManagementService.createUser(tenantA.id(), "shared@example.test", "temp-pwd-1");

        assertThatThrownBy(() ->
            userManagementService.createUser(tenantA.id(), "shared@example.test", "temp-pwd-2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Email already exists in this tenant");

        // Only one row persists — the second call's attempt was rejected before save.
        assertThat(userRepository.findAllByTenantId(tenantA.id()))
            .filteredOn(u -> u.email().equals("shared@example.test"))
            .hasSize(1);
    }
}

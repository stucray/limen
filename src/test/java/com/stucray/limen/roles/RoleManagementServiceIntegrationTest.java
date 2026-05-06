package com.stucray.limen.roles;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("RoleManagementService (per-application role CRUD)")
class RoleManagementServiceIntegrationTest {

    @Autowired RoleManagementService roleManagementService;
    @Autowired RoleRepository roleRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    Application appB;

    @BeforeEach
    void setUp() {
        // Clear any membership-role assignments from membership tests so the
        // role delete below is not blocked by ON DELETE RESTRICT.
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('roles-a', 'roles-b')");

        tenantA = tenantRepository.save(new Tenant(null, "roles-a", "Roles A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "roles-b", "Roles B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
        appB = applicationRepository.save(new Application(null, tenantB.id(), "App B", null, LocalDateTime.now()));
    }

    @Test
    @DisplayName("createRole: persists row scoped to the (application, tenant)")
    void createRoleStoresAndReturnsRow() {
        Role created = roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", "Read-only access");

        assertThat(created.id()).isNotNull();
        assertThat(created.applicationId()).isEqualTo(appA.id());
        assertThat(created.name()).isEqualTo("viewer");
        assertThat(roleRepository.findByIdAndApplicationId(created.id(), appA.id())).isPresent();
    }

    @Test
    @DisplayName("Duplicate role name within the same application is rejected")
    void duplicateNameWithinSameApplicationIsRejected() {
        roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);

        assertThatThrownBy(() -> roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("The same role name is allowed across two applications — uniqueness is per-application")
    void sameNameAcrossDifferentApplicationsIsAllowed() {
        // Same tenant, different application — name reuse is fine because the
        // unique key is (application_id, name), not (tenant_id, name).
        Application secondAppInTenantA = applicationRepository.save(
            new Application(null, tenantA.id(), "App A2", null, LocalDateTime.now())
        );

        roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);
        Role second = roleManagementService.createRole(secondAppInTenantA.id(), tenantA.id(), "viewer", null);

        assertThat(second.id()).isNotNull();
    }

    @Test
    @DisplayName("updateRole: renaming to a name already used by another role in the same app is rejected")
    void renameToExistingNameIsRejected() {
        roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);
        Role editor = roleManagementService.createRole(appA.id(), tenantA.id(), "editor", null);

        assertThatThrownBy(() -> roleManagementService.updateRole(editor.id(), appA.id(), tenantA.id(), "viewer", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("updateRole: changing the description while keeping the same name is allowed")
    void renamingToOwnNameIsAllowed() {
        Role role = roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", "old desc");

        roleManagementService.updateRole(role.id(), appA.id(), tenantA.id(), "viewer", "new desc");

        assertThat(roleRepository.findByIdAndApplicationId(role.id(), appA.id())
            .orElseThrow().description()).isEqualTo("new desc");
    }

    @Test
    @DisplayName("Caller's tenantId mismatching the application's tenant: list/create return 'Application not found'")
    void crossTenantAccessRejected() {
        // appA belongs to tenantA. Calling with tenantB's id must throw.
        assertThatThrownBy(() -> roleManagementService.listRoles(appA.id(), tenantB.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Application not found");

        assertThatThrownBy(() -> roleManagementService.createRole(appA.id(), tenantB.id(), "viewer", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Application not found");
    }

    @Test
    @DisplayName("A role created in app A cannot be addressed through app B")
    void crossApplicationGetRejected() {
        // A role created in appA must not be addressable through appB even by
        // its owning tenant.
        Role roleInA = roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);

        assertThatThrownBy(() -> roleManagementService.getRole(roleInA.id(), appB.id(), tenantB.id()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deleteRole: removes the row")
    void deleteRemovesRow() {
        Role role = roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);

        roleManagementService.deleteRole(role.id(), appA.id(), tenantA.id());

        assertThat(roleRepository.findByIdAndApplicationId(role.id(), appA.id())).isEmpty();
    }

    @Test
    @DisplayName("Deleting an application cascades to all of its roles")
    void deletingApplicationCascadesRoles() {
        Role role = roleManagementService.createRole(appA.id(), tenantA.id(), "viewer", null);

        applicationRepository.delete(appA);

        assertThat(roleRepository.findById(role.id())).isEmpty();
    }
}

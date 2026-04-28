package com.stucray.limen.management.memberships;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.management.roles.Role;
import com.stucray.limen.management.roles.RoleRepository;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ApplicationMembershipServiceIntegrationTest {

    @Autowired ApplicationMembershipService membershipService;
    @Autowired ApplicationMembershipRepository membershipRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    Application appB;
    User aliceA;
    User bobA;
    User adminA;
    User carolB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug IN ('mem-a', 'mem-b'))");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('mem-a', 'mem-b')");

        tenantA = tenantRepository.save(new Tenant(null, "mem-a", "Mem A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "mem-b", "Mem B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
        appB = applicationRepository.save(new Application(null, tenantB.id(), "App B", null, LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice", "x", true, false, false, LocalDateTime.now()));
        bobA = userRepository.save(new User(null, tenantA.id(), "bob",   "x", true, false, false, LocalDateTime.now()));
        adminA = userRepository.save(new User(null, tenantA.id(), "admin", "x", true, false, true,  LocalDateTime.now()));
        carolB = userRepository.save(new User(null, tenantB.id(), "carol", "x", true, false, false, LocalDateTime.now()));
    }

    @Test
    void grantStoresRowWithGrantedAtAndGrantedBy() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThat(m.id()).isNotNull();
        assertThat(m.userId()).isEqualTo(aliceA.id());
        assertThat(m.applicationId()).isEqualTo(appA.id());
        assertThat(m.grantedAt()).isNotNull();
        assertThat(m.grantedBy()).isEqualTo(adminA.id());
        assertThat(m.roleIds()).isEmpty();
    }

    @Test
    void duplicateUserApplicationRejected() {
        membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already a member");
    }

    @Test
    void crossTenantApplicationAccessRejected() {
        // Caller claims tenantA but appB belongs to tenantB.
        assertThatThrownBy(() -> membershipService.listMemberships(appB.id(), tenantA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Application not found");

        assertThatThrownBy(() -> membershipService.grant(appB.id(), tenantA.id(), aliceA.id(), adminA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Application not found");
    }

    @Test
    void grantingUserFromAnotherTenantRejected() {
        // carolB is in tenantB; appA is in tenantA. Even though the caller
        // legitimately controls tenantA, granting a foreign User must fail.
        assertThatThrownBy(() -> membershipService.grant(appA.id(), tenantA.id(), carolB.id(), adminA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    void uniqueConstraintHonouredAtRepositoryLevel() {
        membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> membershipRepository.save(
            new ApplicationMembership(null, aliceA.id(), appA.id(), LocalDateTime.now(), adminA.id(), Set.of())
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateRolesAssignsSet() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id(), editor.id()));

        ApplicationMembership reloaded = membershipService.getMembership(m.id(), appA.id(), tenantA.id());
        assertThat(reloaded.roleIds()).containsExactlyInAnyOrder(viewer.id(), editor.id());
    }

    @Test
    void updateRolesReplacesExistingSet() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id(), editor.id()));
        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(editor.id()));

        ApplicationMembership reloaded = membershipService.getMembership(m.id(), appA.id(), tenantA.id());
        assertThat(reloaded.roleIds()).containsExactly(editor.id());
    }

    @Test
    void updateRolesEmptyClearsSet() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of());

        assertThat(membershipService.getMembership(m.id(), appA.id(), tenantA.id()).roleIds()).isEmpty();
    }

    @Test
    void cannotAssignRoleFromAnotherApplication() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        // Role belongs to a *different* tenant's application — clearly not assignable.
        Role foreign = roleRepository.save(new Role(null, appB.id(), "viewer", null, LocalDateTime.now()));

        assertThatThrownBy(() -> membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(foreign.id())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong to this application");
    }

    @Test
    void cannotAssignRoleFromAnotherAppInSameTenant() {
        // Same tenant, different app — still must be rejected.
        Application appA2 = applicationRepository.save(new Application(null, tenantA.id(), "App A2", null, LocalDateTime.now()));
        Role roleInA2 = roleRepository.save(new Role(null, appA2.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(roleInA2.id())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong to this application");
    }

    @Test
    void revokeRemovesRowAndRoleAssignments() {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        membershipService.revoke(m.id(), appA.id(), tenantA.id());

        assertThat(membershipRepository.findById(m.id())).isEmpty();
        Integer joinCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM application_membership_role WHERE application_membership_id = ?",
            Integer.class, m.id()
        );
        assertThat(joinCount).isZero();
        // The Role itself remains — only the assignment is gone.
        assertThat(roleRepository.findById(viewer.id())).isPresent();
    }

    @Test
    void roleAssignedToMembershipCannotBeDeleted() {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        // ON DELETE RESTRICT on application_membership_role.role_id surfaces
        // as a DataIntegrityViolationException — exactly what RolesController
        // already catches and translates into a user-facing error.
        assertThatThrownBy(() -> roleRepository.delete(viewer))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingApplicationCascadesMembershipWithoutRoleAssignments() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        applicationRepository.delete(appA);

        assertThat(membershipRepository.findById(m.id())).isEmpty();
    }

    @Test
    void deletingApplicationWithAssignedRolesFailsRestrict() {
        // The PRD #39 RESTRICT on application_membership_role.role_id protects
        // against silent role-assignment loss. As a side effect, an Application
        // cannot be deleted while any of its Roles are still assigned to a
        // Membership — the role-cascade fires before the membership-cascade
        // and Postgres rejects the role delete. Admin must clear assignments
        // (or revoke the membership) first, mirroring the existing
        // "cannot delete an Application with attached Clients" rule.
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        membershipService.updateRoles(m.id(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        assertThatThrownBy(() -> applicationRepository.delete(appA))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesMembership() {
        membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        userRepository.delete(aliceA);

        assertThat(membershipRepository.findAllByApplicationId(appA.id())).isEmpty();
    }

    @Test
    void deletingGranterNullifiesGrantedBy() {
        ApplicationMembership m = membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        userRepository.delete(adminA);

        ApplicationMembership reloaded = membershipRepository.findById(m.id()).orElseThrow();
        assertThat(reloaded.grantedBy()).isNull();
    }

    @Test
    void listGrantableUsersExcludesExistingMembers() {
        membershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        var grantable = membershipService.listGrantableUsers(appA.id(), tenantA.id());

        assertThat(grantable).extracting(User::username).containsExactlyInAnyOrder("bob", "admin");
    }
}

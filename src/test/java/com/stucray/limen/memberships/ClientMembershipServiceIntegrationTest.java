package com.stucray.limen.memberships;

import com.stucray.limen.clients.CreateClientCommand;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.ClientManagementService;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.roles.Role;
import com.stucray.limen.roles.RoleRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("ClientMembershipService (per-client memberships derived from app memberships)")
class ClientMembershipServiceIntegrationTest {

    @Autowired ClientMembershipService clientMembershipService;
    @Autowired ClientMembershipRepository clientMembershipRepository;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ApplicationMembershipRepository applicationMembershipRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    Application appA2;       // same tenant, different app — used for cross-app checks
    Application appB;        // foreign tenant — used for cross-tenant checks
    TenantClient clientA;    // child of appA in tenantA
    TenantClient clientA2;   // child of appA2 in tenantA
    TenantClient clientB;    // child of appB in tenantB
    User aliceA;
    User bobA;
    User adminA;
    User carolB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug IN ('cm-a', 'cm-b'))");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('cm-a', 'cm-b')");

        tenantA = tenantRepository.save(new Tenant(null, "cm-a", "CM A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "cm-b", "CM B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA  = applicationRepository.save(new Application(null, tenantA.id(), "App A",  null, LocalDateTime.now()));
        appA2 = applicationRepository.save(new Application(null, tenantA.id(), "App A2", null, LocalDateTime.now()));
        appB  = applicationRepository.save(new Application(null, tenantB.id(), "App B",  null, LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice@example.test", "x", true, false, false, true, LocalDateTime.now()));
        bobA   = userRepository.save(new User(null, tenantA.id(), "bob@example.test",   "x", true, false, false, true, LocalDateTime.now()));
        adminA = userRepository.save(new User(null, tenantA.id(), "admin@example.test", "x", true, false, true, true,  LocalDateTime.now()));
        carolB = userRepository.save(new User(null, tenantB.id(), "carol@example.test", "x", true, false, false, true, LocalDateTime.now()));

        clientA  = createClient(appA.id(),  tenantA.id(), "client-a");
        clientA2 = createClient(appA2.id(), tenantA.id(), "client-a2");
        clientB  = createClient(appB.id(),  tenantB.id(), "client-b");
    }

    private TenantClient createClient(Long applicationId, Long tenantId, String name) {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(new CreateClientCommand(
            applicationId, tenantId, name,
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/callback"), Set.of(), Set.of("openid"),
            false, true, 5, 30, false
        ));
        return result.client();
    }

    @Test
    @DisplayName("grant: persists row with granted_at, granted_by, and FK back to the app membership")
    void grantStoresRowWithGrantedAtGrantedByAndAppMembershipFk() {
        ApplicationMembership am = applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        ClientMembership cm = clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        );

        assertThat(cm.id()).isNotNull();
        assertThat(cm.userId()).isEqualTo(aliceA.id());
        assertThat(cm.clientMetadataId()).isEqualTo(clientA.id());
        assertThat(cm.applicationMembershipId()).isEqualTo(am.id());
        assertThat(cm.grantedAt()).isNotNull();
        assertThat(cm.grantedBy()).isEqualTo(adminA.id());
        assertThat(cm.roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Granting a client membership requires an existing application membership (gates downward)")
    void grantRequiresExistingApplicationMembership() {
        // alice has no App Membership for appA.
        assertThatThrownBy(() -> clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a member of this application");
    }

    @Test
    @DisplayName("Granting a client membership for app A2 fails when the user is only a member of app A")
    void grantRejectedIfUserOnlyHasAppMembershipForDifferentApp() {
        // Cross-table invariant: alice has App Membership for appA only, but
        // tries to be granted Client Membership on a Client of appA2. The
        // service looks up App Membership for (alice, appA2) — finds none,
        // rejects. There is no service-call shape that could attach alice's
        // appA membership to a Client of appA2.
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> clientMembershipService.grant(
            clientA2.registeredClientId(), appA2.id(), tenantA.id(), aliceA.id(), adminA.id()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a member of this application");
    }

    @Test
    @DisplayName("Granting the same user to the same client twice is rejected with 'already a member'")
    void duplicateUserClientRejected() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already a member");
    }

    @Test
    @DisplayName("Caller's tenantId mismatching the client's tenant: list/grant return 'Client not found'")
    void crossTenantClientAccessRejected() {
        // Caller claims tenantA but clientB belongs to tenantB.
        assertThatThrownBy(() -> clientMembershipService.listMemberships(clientB.registeredClientId(), appA.id(), tenantA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Client not found");

        assertThatThrownBy(() -> clientMembershipService.grant(
            clientB.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Client not found");
    }

    @Test
    @DisplayName("URL mismatch (clientA2 listed under appA) is rejected even within the same tenant")
    void mismatchedClientApplicationPathRejected() {
        // URL claims clientA2 (which belongs to appA2) hangs off appA. Reject
        // even within the same tenant — the URL hierarchy must be honest.
        assertThatThrownBy(() -> clientMembershipService.listMemberships(clientA2.registeredClientId(), appA.id(), tenantA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Client not found");
    }

    @Test
    @DisplayName("Granting a user from a different tenant is rejected with 'User not found'")
    void grantingUserFromAnotherTenantRejected() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), carolB.id(), adminA.id()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("Repository-level: (user_id, client_metadata_id) unique constraint surfaces as DataIntegrityViolation")
    void uniqueConstraintHonouredAtRepositoryLevel() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ApplicationMembership am = applicationMembershipRepository.findByUserIdAndApplicationId(aliceA.id(), appA.id()).orElseThrow();
        clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        assertThatThrownBy(() -> clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), adminA.id(), Set.of()
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("updateRoles: assigns a set of roles to an existing client membership")
    void updateRolesAssignsSet() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        clientMembershipService.updateRoles(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of(viewer.id(), editor.id()));

        ClientMembership reloaded = clientMembershipService.getMembership(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id());
        assertThat(reloaded.roleIds()).containsExactlyInAnyOrder(viewer.id(), editor.id());
    }

    @Test
    @DisplayName("updateRoles: passing an empty set clears all role assignments")
    void updateRolesEmptyClearsSet() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        clientMembershipService.updateRoles(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        clientMembershipService.updateRoles(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of());

        assertThat(clientMembershipService.getMembership(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id())
            .roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Roles from a sibling app (same tenant) cannot be assigned to a client membership")
    void cannotAssignRoleFromAnotherApplication() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        // Role belongs to appA2 (same tenant, different app) — must be rejected.
        Role foreign = roleRepository.save(new Role(null, appA2.id(), "viewer", null, LocalDateTime.now()));

        assertThatThrownBy(() -> clientMembershipService.updateRoles(
            cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of(foreign.id())
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not belong to this application");
    }

    @Test
    @DisplayName("revoke: deletes the membership and its role-assignment join rows; role itself survives")
    void revokeRemovesRowAndRoleAssignments() {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.updateRoles(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        clientMembershipService.revoke(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id());

        assertThat(clientMembershipRepository.findById(cm.id())).isEmpty();
        Integer joinCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM client_membership_role WHERE client_membership_id = ?",
            Integer.class, cm.id()
        );
        assertThat(joinCount).isZero();
        assertThat(roleRepository.findById(viewer.id())).isPresent();
    }

    @Test
    @DisplayName("A Role still assigned to a client membership cannot be deleted (RESTRICT)")
    void roleAssignedToClientMembershipCannotBeDeleted() {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.updateRoles(cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(), Set.of(viewer.id()));

        assertThatThrownBy(() -> roleRepository.delete(viewer))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Revoking an application membership cascades to all of its derived client memberships")
    void revokingApplicationMembershipCascadesClientMemberships() {
        // PRD #39 decision 4: hard FK + CASCADE from client_membership.application_membership_id
        // means revoking App Membership atomically revokes all derived Client Memberships.
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ApplicationMembership am = applicationMembershipRepository
            .findByUserIdAndApplicationId(aliceA.id(), appA.id()).orElseThrow();
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        applicationMembershipService.revoke(am.id(), appA.id(), tenantA.id());

        assertThat(clientMembershipRepository.findById(cm.id())).isEmpty();
        assertThat(clientMembershipRepository.findAllByClientMetadataId(clientA.id())).isEmpty();
    }

    @Test
    @DisplayName("Deleting a client cascades to its client memberships")
    void deletingClientCascadesClientMembership() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        clientManagementService.deleteClient(clientA.registeredClientId(), tenantA.id());

        assertThat(clientMembershipRepository.findById(cm.id())).isEmpty();
    }

    @Test
    @DisplayName("Deleting a user cascades to all of their client memberships")
    void deletingUserCascadesClientMembership() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        userRepository.delete(aliceA);

        assertThat(clientMembershipRepository.findAllByClientMetadataId(clientA.id())).isEmpty();
    }

    @Test
    @DisplayName("Deleting the granter (admin) nullifies granted_by on existing client memberships")
    void deletingGranterNullifiesGrantedBy() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), bobA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), bobA.id(), adminA.id()
        );

        userRepository.delete(adminA);

        ClientMembership reloaded = clientMembershipRepository.findById(cm.id()).orElseThrow();
        assertThat(reloaded.grantedBy()).isNull();
    }

    @Test
    @DisplayName("listGrantableUsers: includes only app members who don't yet have a client membership")
    void listGrantableUsersOnlyIncludesAppMembersWithoutClientMembership() {
        // alice & bob both have App Membership for appA; alice already has Client Membership.
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        applicationMembershipService.grant(appA.id(), tenantA.id(), bobA.id(),   adminA.id());
        clientMembershipService.grant(clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id());

        var grantable = clientMembershipService.listGrantableUsers(clientA.registeredClientId(), appA.id(), tenantA.id());

        // admin has no App Membership → not eligible. alice has Client Membership → not eligible.
        assertThat(grantable).extracting(User::email).containsExactly("bob@example.test");
    }
}

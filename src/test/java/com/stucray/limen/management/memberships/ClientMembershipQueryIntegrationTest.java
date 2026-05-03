package com.stucray.limen.management.memberships;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
import com.stucray.limen.management.clients.ClientManagementService;
import com.stucray.limen.management.clients.TenantClient;
import com.stucray.limen.management.roles.Role;
import com.stucray.limen.management.roles.RoleRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("ClientMembershipQuery (read-side; used by token issuance)")
class ClientMembershipQueryIntegrationTest {

    @Autowired ClientMembershipQuery clientMembershipQuery;
    @Autowired ClientMembershipService clientMembershipService;
    @Autowired ApplicationMembershipService applicationMembershipService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired RoleRepository roleRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    Application appB;
    TenantClient clientA;
    TenantClient clientB;
    User aliceA;
    User adminA;
    User carolB;
    User adminB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug IN ('cmq-a', 'cmq-b'))");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('cmq-a', 'cmq-b')");

        tenantA = tenantRepository.save(new Tenant(null, "cmq-a", "CMQ A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "cmq-b", "CMQ B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
        appB = applicationRepository.save(new Application(null, tenantB.id(), "App B", null, LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice", "x", true, false, false, true, LocalDateTime.now()));
        adminA = userRepository.save(new User(null, tenantA.id(), "admin", "x", true, false, true, true,  LocalDateTime.now()));
        carolB = userRepository.save(new User(null, tenantB.id(), "carol", "x", true, false, false, true, LocalDateTime.now()));
        adminB = userRepository.save(new User(null, tenantB.id(), "admin-b", "x", true, false, true, true, LocalDateTime.now()));

        clientA = createClient(appA.id(), tenantA.id(), "client-a");
        clientB = createClient(appB.id(), tenantB.id(), "client-b");
    }

    private TenantClient createClient(Long applicationId, Long tenantId, String name) {
        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            applicationId, tenantId, name,
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/callback"), Set.of(), Set.of("openid"),
            false, true, 5, 30, false
        );
        return result.client();
    }

    @Test
    @DisplayName("rolesFor: returns the assigned role names, sorted alphabetically")
    void rolesForReturnsAssignedRolesAlphabetical() {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));
        Role admin  = roleRepository.save(new Role(null, appA.id(), "admin",  null, LocalDateTime.now()));
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        ClientMembership cm = clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        );
        clientMembershipService.updateRoles(
            cm.id(), clientA.registeredClientId(), appA.id(), tenantA.id(),
            Set.of(viewer.id(), editor.id(), admin.id())
        );

        List<String> roles = clientMembershipQuery.rolesFor(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );

        assertThat(roles).containsExactly("admin", "editor", "viewer");
    }

    @Test
    @DisplayName("rolesFor: returns empty when there is no client membership")
    void rolesForReturnsEmptyWhenNoMembership() {
        List<String> roles = clientMembershipQuery.rolesFor(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );
        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("rolesFor: returns empty when the membership exists but has no roles assigned")
    void rolesForReturnsEmptyWhenMembershipExistsButNoRolesAssigned() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        );

        List<String> roles = clientMembershipQuery.rolesFor(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );

        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("rolesFor: defensive tenant_id predicate rejects rows when caller passes the wrong tenant")
    void rolesForIsCrossTenantSafe() {
        // carol is in tenantB and has full Membership + Role for clientB.
        Role role = roleRepository.save(new Role(null, appB.id(), "viewer", null, LocalDateTime.now()));
        applicationMembershipService.grant(appB.id(), tenantB.id(), carolB.id(), adminB.id());
        ClientMembership cm = clientMembershipService.grant(
            clientB.registeredClientId(), appB.id(), tenantB.id(), carolB.id(), adminB.id()
        );
        clientMembershipService.updateRoles(
            cm.id(), clientB.registeredClientId(), appB.id(), tenantB.id(), Set.of(role.id())
        );

        // Caller asks for carol's Roles on clientB but with the WRONG tenant id
        // (tenantA). The defensive tenant_id predicate must reject the row even
        // though the FK chain alone would already match.
        List<String> rolesWithWrongTenant = clientMembershipQuery.rolesFor(
            carolB.id(), clientB.registeredClientId(), tenantA.id()
        );

        assertThat(rolesWithWrongTenant).isEmpty();
    }

    @Test
    @DisplayName("rolesFor: returns empty when the user belongs to a different tenant from the requested client")
    void rolesForRejectsForeignTenantUser() {
        // alice (tenantA) has no Membership for clientB (tenantB) — must be empty
        // even when caller passes tenantB id, simulating a stray cross-tenant query.
        List<String> roles = clientMembershipQuery.rolesFor(
            aliceA.id(), clientB.registeredClientId(), tenantB.id()
        );
        assertThat(roles).isEmpty();
    }

    @Test
    @DisplayName("hasMembership: returns true when the user has a client membership for that registered_client")
    void hasMembershipReturnsTrueForExistingMembership() {
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        );

        boolean has = clientMembershipQuery.hasMembership(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );

        assertThat(has).isTrue();
    }

    @Test
    @DisplayName("hasMembership: returns true even when the membership has zero roles assigned")
    void hasMembershipReturnsTrueEvenWithNoRoles() {
        // Membership-without-Roles is still a Membership — slice 5's gate
        // depends on this distinction (presence of row, not Roles count).
        applicationMembershipService.grant(appA.id(), tenantA.id(), aliceA.id(), adminA.id());
        clientMembershipService.grant(
            clientA.registeredClientId(), appA.id(), tenantA.id(), aliceA.id(), adminA.id()
        );

        boolean has = clientMembershipQuery.hasMembership(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );

        assertThat(has).isTrue();
    }

    @Test
    @DisplayName("hasMembership: returns false when no client membership row exists")
    void hasMembershipReturnsFalseWhenAbsent() {
        boolean has = clientMembershipQuery.hasMembership(
            aliceA.id(), clientA.registeredClientId(), tenantA.id()
        );
        assertThat(has).isFalse();
    }

    @Test
    @DisplayName("hasMembership: returns false when caller's tenant_id does not match the row's tenant")
    void hasMembershipIsCrossTenantSafe() {
        applicationMembershipService.grant(appB.id(), tenantB.id(), carolB.id(), adminB.id());
        clientMembershipService.grant(
            clientB.registeredClientId(), appB.id(), tenantB.id(), carolB.id(), adminB.id()
        );

        boolean has = clientMembershipQuery.hasMembership(
            carolB.id(), clientB.registeredClientId(), tenantA.id()
        );

        assertThat(has).isFalse();
    }

    @Test
    @DisplayName("rolesFor: tolerates any-arg-null and returns empty rather than throwing")
    void rolesForToleratesNullArgs() {
        assertThat(clientMembershipQuery.rolesFor(null, "any", tenantA.id())).isEmpty();
        assertThat(clientMembershipQuery.rolesFor(aliceA.id(), null, tenantA.id())).isEmpty();
        assertThat(clientMembershipQuery.rolesFor(aliceA.id(), "any", null)).isEmpty();
    }

    @Test
    @DisplayName("hasMembership: tolerates any-arg-null and returns false rather than throwing")
    void hasMembershipToleratesNullArgs() {
        assertThat(clientMembershipQuery.hasMembership(null, "any", tenantA.id())).isFalse();
        assertThat(clientMembershipQuery.hasMembership(aliceA.id(), null, tenantA.id())).isFalse();
        assertThat(clientMembershipQuery.hasMembership(aliceA.id(), "any", null)).isFalse();
    }
}

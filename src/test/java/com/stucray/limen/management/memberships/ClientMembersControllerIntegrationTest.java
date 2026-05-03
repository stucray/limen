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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ClientMembersController (per-client member CRUD on the management UI)")
class ClientMembersControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ApplicationMembershipRepository appMembershipRepository;
    @Autowired ClientMembershipRepository clientMembershipRepository;
    @Autowired ClientManagementService clientManagementService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    User ownerA;
    User aliceA;
    TenantClient clientA;
    MockHttpSession sessionA;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenantA = tenantRepository.save(new Tenant(null, "client-mem-a", "Client Mem A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "client-mem-b", "Client Mem B", TenantStatus.ACTIVE, LocalDateTime.now()));
        ownerA = userRepository.save(new User(null, tenantA.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, true,  LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", "desc", LocalDateTime.now()));
        clientA = clientManagementService.createClient(
            appA.id(), tenantA.id(), "client-a",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/callback"), Set.of(), Set.of("openid"),
            false, true, 5, 30, false
        ).client();

        MvcResult login = mockMvc.perform(post("/manage/t/client-mem-a/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    private String url(String suffix) {
        return "/manage/t/client-mem-a/applications/" + appA.id() + "/clients/" + clientA.registeredClientId() + suffix;
    }

    @Test
    @DisplayName("Owner can list a client's members")
    void ownerCanListClientMembers() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(get(url("/members")).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")));
    }

    @Test
    @DisplayName("Owner can grant a client membership to an existing application member")
    void ownerCanGrantClientMembership() throws Exception {
        appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(post(url("/members"))
                .session(sessionA).with(csrf())
                .param("userId", aliceA.id().toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(url("/members")));

        var memberships = clientMembershipRepository.findAllByClientMetadataId(clientA.id());
        assertThat(memberships).hasSize(1);
        ClientMembership saved = memberships.get(0);
        assertThat(saved.userId()).isEqualTo(aliceA.id());
        assertThat(saved.grantedBy()).isEqualTo(ownerA.id());
        assertThat(saved.grantedAt()).isNotNull();
    }

    @Test
    @DisplayName("Granting a client membership for a user without an application membership re-renders with 'not a member of this application'")
    void grantWithoutAppMembershipRedisplaysFormWithError() throws Exception {
        // alice has no App Membership for appA, so the eligibility gate fires.
        mockMvc.perform(post(url("/members"))
                .session(sessionA).with(csrf())
                .param("userId", aliceA.id().toString()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("not a member of this application")));
    }

    @Test
    @DisplayName("Owner can assign multiple roles to a client membership via the edit form")
    void ownerCanAssignRolesViaEditForm() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        ClientMembership cm = clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        mockMvc.perform(post(url("/members/" + cm.id() + "/edit"))
                .session(sessionA).with(csrf())
                .param("roleIds", viewer.id().toString())
                .param("roleIds", editor.id().toString()))
            .andExpect(status().is3xxRedirection());

        ClientMembership reloaded = clientMembershipRepository.findById(cm.id()).orElseThrow();
        assertThat(reloaded.roleIds()).containsExactlyInAnyOrder(viewer.id(), editor.id());
    }

    @Test
    @DisplayName("Submitting the edit form with an unknown role id re-renders with a 'Role not found' error")
    void editFormSubmitWithUnknownRoleIdRedisplaysFormWithError() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        ClientMembership cm = clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(post(url("/members/" + cm.id() + "/edit"))
                .session(sessionA).with(csrf())
                .param("roleIds", "999999"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Role not found: 999999")));

        assertThat(clientMembershipRepository.findById(cm.id()).orElseThrow().roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Edit form GET renders the existing membership and the available roles")
    void editFormGetRendersTemplateWithMembershipDetails() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        ClientMembership cm = clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));

        mockMvc.perform(get(url("/members/" + cm.id() + "/edit")).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("viewer")));
    }

    @Test
    @DisplayName("New-grant form GET lists only users with an application membership but no client membership yet")
    void newFormGetRendersGrantableUsers() throws Exception {
        appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(get(url("/members/new")).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")));
    }

    @Test
    @DisplayName("Submitting edit with no roleIds clears all role assignments on the membership")
    void editFormSubmitWithNoRoleIdsClearsAssignments() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ClientMembership cm = clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(),
            Set.of(new ClientMembershipRole(viewer.id()))
        ));

        mockMvc.perform(post(url("/members/" + cm.id() + "/edit"))
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(clientMembershipRepository.findById(cm.id()).orElseThrow().roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Owner can revoke a client membership via POST /members/{id}/delete")
    void ownerCanRevokeClientMembership() throws Exception {
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        ClientMembership cm = clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(post(url("/members/" + cm.id() + "/delete"))
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(clientMembershipRepository.findById(cm.id())).isEmpty();
    }

    @Test
    @DisplayName("Tenant B session is force-redirected to tenant A's login when reaching tenant A's client members")
    void tenantBSessionCannotReachTenantAClientMembers() throws Exception {
        userRepository.save(new User(null, tenantB.id(), "ownerB@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/client-mem-b/login")
                .param("email", "ownerB@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        mockMvc.perform(get(url("/members")).session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/client-mem-a/login"));
    }

    @Test
    @DisplayName("Clients-list page links each row to its /members page")
    void clientMembersLinkAppearsOnClientsList() throws Exception {
        mockMvc.perform(get("/manage/t/client-mem-a/applications/" + appA.id() + "/clients").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                url("/members")
            )));
    }
}

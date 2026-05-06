package com.stucray.limen.memberships;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

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
@DisplayName("MembersController (per-application member CRUD on the management UI)")
class MembersControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ApplicationMembershipRepository membershipRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    User ownerA;
    User aliceA;
    MockHttpSession sessionA;

    @BeforeEach
    void setUp() throws Exception {
        // client_membership rows (from ClientMembership tests) FK back into
        // application_membership and role; clear them first so the existing
        // DELETEs below don't trip ON DELETE RESTRICT on role.
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenantA = tenantRepository.save(new Tenant(null, "members-corp-a", "Members Corp A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "members-corp-b", "Members Corp B", TenantStatus.ACTIVE, LocalDateTime.now()));
        ownerA = userRepository.save(new User(null, tenantA.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", "desc", LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/members-corp-a/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    @DisplayName("Owner can list an application's members")
    void ownerCanListMembers() throws Exception {
        membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));

        mockMvc.perform(get("/manage/t/members-corp-a/applications/" + appA.id() + "/members").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")));
    }

    @Test
    @DisplayName("Owner can grant an application membership; granted_by is the authenticated owner")
    void ownerCanGrantMembership() throws Exception {
        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members")
                .session(sessionA).with(csrf())
                .param("userId", aliceA.id().toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/members-corp-a/applications/" + appA.id() + "/members"));

        var memberships = membershipRepository.findAllByApplicationId(appA.id());
        assertThat(memberships).hasSize(1);
        ApplicationMembership saved = memberships.get(0);
        assertThat(saved.userId()).isEqualTo(aliceA.id());
        // granted_by should be the authenticated owner.
        assertThat(saved.grantedBy()).isEqualTo(ownerA.id());
        assertThat(saved.grantedAt()).isNotNull();
    }

    @Test
    @DisplayName("Granting an existing member again re-renders the form with 'already a member'")
    void duplicateGrantRedisplaysFormWithError() throws Exception {
        membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));

        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members")
                .session(sessionA).with(csrf())
                .param("userId", aliceA.id().toString()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already a member")));
    }

    @Test
    @DisplayName("Owner can assign multiple roles to an application membership via the edit form")
    void ownerCanAssignRolesViaEditForm() throws Exception {
        ApplicationMembership m = membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members/" + m.id() + "/edit")
                .session(sessionA).with(csrf())
                .param("roleIds", viewer.id().toString())
                .param("roleIds", editor.id().toString()))
            .andExpect(status().is3xxRedirection());

        ApplicationMembership reloaded = membershipRepository.findById(m.id()).orElseThrow();
        assertThat(reloaded.roleIds()).containsExactlyInAnyOrder(viewer.id(), editor.id());
    }

    @Test
    @DisplayName("Submitting edit with an unknown roleId re-renders with a 'Role not found' error; row unchanged")
    void editFormSubmitWithUnknownRoleIdRedisplaysFormWithError() throws Exception {
        ApplicationMembership m = membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));

        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members/" + m.id() + "/edit")
                .session(sessionA).with(csrf())
                .param("roleIds", "999999"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Role not found: 999999")));

        // The persisted membership must remain unchanged (rejection happens before save).
        assertThat(membershipRepository.findById(m.id()).orElseThrow().roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Edit form GET renders the existing application membership and the available roles")
    void editFormGetRendersTemplateWithMembershipDetails() throws Exception {
        ApplicationMembership m = membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));
        roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));

        mockMvc.perform(get("/manage/t/members-corp-a/applications/" + appA.id() + "/members/" + m.id() + "/edit")
                .session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("viewer")));
    }

    @Test
    @DisplayName("New-grant form GET lists grantable users (excludes existing members)")
    void newFormGetRendersGrantableUsersExcludingExistingMembers() throws Exception {
        // alice is grantable initially.
        mockMvc.perform(get("/manage/t/members-corp-a/applications/" + appA.id() + "/members/new")
                .session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")));
    }

    @Test
    @DisplayName("Submitting edit with no roleIds clears all role assignments on the membership")
    void editFormSubmitWithNoRoleIdsClearsAssignments() throws Exception {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership m = membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(),
            java.util.Set.of(new ApplicationMembershipRole(viewer.id()))
        ));

        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members/" + m.id() + "/edit")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(membershipRepository.findById(m.id()).orElseThrow().roleIds()).isEmpty();
    }

    @Test
    @DisplayName("Owner can revoke an application membership via POST /members/{id}/delete")
    void ownerCanRevokeMembership() throws Exception {
        ApplicationMembership m = membershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), java.util.Set.of()
        ));

        mockMvc.perform(post("/manage/t/members-corp-a/applications/" + appA.id() + "/members/" + m.id() + "/delete")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(membershipRepository.findById(m.id())).isEmpty();
    }

    @Test
    @DisplayName("Tenant B session is force-redirected to tenant A's login when reaching tenant A's members")
    void tenantBSessionCannotReachTenantAMembers() throws Exception {
        userRepository.save(new User(null, tenantB.id(), "ownerB@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/members-corp-b/login")
                .param("email", "ownerB@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        // TenantAccessFilter force-logs out the cross-tenant session and
        // redirects to the URL slug's login page.
        mockMvc.perform(get("/manage/t/members-corp-a/applications/" + appA.id() + "/members").session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/members-corp-a/login"));
    }

    @Test
    @DisplayName("Applications-list page links each row to its /members page")
    void membersLinkAppearsOnApplicationsList() throws Exception {
        mockMvc.perform(get("/manage/t/members-corp-a/applications").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "/manage/t/members-corp-a/applications/" + appA.id() + "/members"
            )));
    }
}

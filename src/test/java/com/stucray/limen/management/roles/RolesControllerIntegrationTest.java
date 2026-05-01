package com.stucray.limen.management.roles;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RolesController (per-application role CRUD on the management UI)")
class RolesControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;
    MockHttpSession sessionA;

    @BeforeEach
    void setUp() throws Exception {
        // Clear any membership-role assignments from membership tests so the
        // role delete below is not blocked by ON DELETE RESTRICT.
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenantA = tenantRepository.save(new Tenant(null, "roles-corp-a", "Roles Corp A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "roles-corp-b", "Roles Corp B", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(null, tenantA.id(), "owner", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", "desc", LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/roles-corp-a/login")
                .param("username", "owner").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    @DisplayName("Owner can list the application's roles")
    void ownerCanListRoles() throws Exception {
        roleRepository.save(new Role(null, appA.id(), "viewer", "read-only", LocalDateTime.now()));

        mockMvc.perform(get("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("viewer")));
    }

    @Test
    @DisplayName("Owner can create a role via POST /roles")
    void ownerCanCreateRole() throws Exception {
        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles")
                .session(sessionA).with(csrf())
                .param("name", "editor").param("description", "Can edit"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles"));

        assertThat(roleRepository.findAllByApplicationId(appA.id()))
            .extracting(Role::name).containsExactly("editor");
    }

    @Test
    @DisplayName("Creating a role with a name already used in the same app re-renders with 'already exists'")
    void duplicateNameRedisplaysFormWithError() throws Exception {
        roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles")
                .session(sessionA).with(csrf())
                .param("name", "viewer").param("description", "dup"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @DisplayName("Owner can edit a role's name and description via POST /roles/{id}/edit")
    void ownerCanEditRole() throws Exception {
        Role role = roleRepository.save(new Role(null, appA.id(), "old", "old desc", LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/" + role.id() + "/edit")
                .session(sessionA).with(csrf())
                .param("name", "new").param("description", "new desc"))
            .andExpect(status().is3xxRedirection());

        Role reloaded = roleRepository.findByIdAndApplicationId(role.id(), appA.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo("new");
        assertThat(reloaded.description()).isEqualTo("new desc");
    }

    @Test
    @DisplayName("Renaming a role to an existing name re-renders the form; row remains unchanged")
    void editWithDuplicateNameRedisplaysFormWithError() throws Exception {
        roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Role editor = roleRepository.save(new Role(null, appA.id(), "editor", null, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/" + editor.id() + "/edit")
                .session(sessionA).with(csrf())
                .param("name", "viewer").param("description", "collision"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already exists")));

        Role reloaded = roleRepository.findByIdAndApplicationId(editor.id(), appA.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo("editor");
    }

    @Test
    @DisplayName("Edit form GET renders the existing role")
    void editFormGetRendersTemplateWithRole() throws Exception {
        Role role = roleRepository.save(new Role(null, appA.id(), "viewer", "read-only", LocalDateTime.now()));

        mockMvc.perform(get("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/" + role.id() + "/edit")
                .session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("viewer")));
    }

    @Test
    @DisplayName("New-role form GET renders the template")
    void newFormGetRendersTemplate() throws Exception {
        mockMvc.perform(get("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/new").session(sessionA))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Deleting a role still assigned to a membership re-renders the list with 'Cannot delete'")
    void deleteOfRoleAssignedToMembershipRedisplaysListWithError() throws Exception {
        // Insert a role then directly attach it to an application_membership_role
        // row so the FK ON DELETE RESTRICT fires when the role is deleted. Using
        // raw SQL keeps the test independent of the ApplicationMembershipService.
        Role role = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        Long ownerId = userRepository.findByUsernameAndTenantId("owner", tenantA.id()).orElseThrow().id();
        Long membershipId = jdbcTemplate.queryForObject(
            "INSERT INTO application_membership (user_id, application_id, granted_at, granted_by) VALUES (?,?,NOW(),?) RETURNING id",
            Long.class, ownerId, appA.id(), ownerId
        );
        jdbcTemplate.update(
            "INSERT INTO application_membership_role (application_membership_id, role_id) VALUES (?,?)",
            membershipId, role.id()
        );

        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/" + role.id() + "/delete")
                .session(sessionA).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Cannot delete a role that is still assigned")));

        assertThat(roleRepository.findById(role.id())).isPresent();
    }

    @Test
    @DisplayName("Owner can delete an unassigned role via POST /roles/{id}/delete")
    void ownerCanDeleteRole() throws Exception {
        Role role = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles/" + role.id() + "/delete")
                .session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(roleRepository.findById(role.id())).isEmpty();
    }

    @Test
    @DisplayName("Tenant B session is force-redirected to tenant A's login when reaching tenant A's roles")
    void tenantBSessionCannotReachTenantARoles() throws Exception {
        userRepository.save(new User(null, tenantB.id(), "ownerB", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/roles-corp-b/login")
                .param("username", "ownerB").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        // TenantAccessFilter force-logs out the cross-tenant session and
        // redirects to the URL slug's login page.
        mockMvc.perform(get("/manage/t/roles-corp-a/applications/" + appA.id() + "/roles").session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/roles-corp-a/login"));
    }

    @Test
    @DisplayName("Applications-list page links each row to its /roles page")
    void rolesLinkAppearsOnApplicationsList() throws Exception {
        mockMvc.perform(get("/manage/t/roles-corp-a/applications").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "/manage/t/roles-corp-a/applications/" + appA.id() + "/roles"
            )));
    }
}

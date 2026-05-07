package com.stucray.limen.users;

import com.stucray.limen.clients.CreateClientCommand;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.ClientManagementService;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.memberships.ApplicationMembership;
import com.stucray.limen.memberships.ApplicationMembershipRepository;
import com.stucray.limen.memberships.ApplicationMembershipRole;
import com.stucray.limen.memberships.ClientMembership;
import com.stucray.limen.memberships.ClientMembershipRepository;
import com.stucray.limen.memberships.ClientMembershipRole;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("User detail page: read-only view of a user's application + client memberships")
class UserDetailControllerIntegrationTest {

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
    User ownerA;
    User aliceA;
    Application appA;
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

        tenantA = tenantRepository.save(new Tenant(null, "user-detail-a", "User Detail A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "user-detail-b", "User Detail B", TenantStatus.ACTIVE, LocalDateTime.now()));
        ownerA = userRepository.save(new User(null, tenantA.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, true,  LocalDateTime.now()));
        aliceA = userRepository.save(new User(null, tenantA.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "Acme Web", "desc", LocalDateTime.now()));
        clientA = clientManagementService.createClient(new CreateClientCommand(
            appA.id(), tenantA.id(), "acme-spa",
            Set.of(AuthorizationGrantType.AUTHORIZATION_CODE),
            Set.of("http://localhost/callback"), Set.of(), Set.of("openid"),
            false, true, 5, 30, false
        )).client();

        MvcResult login = mockMvc.perform(post("/manage/t/user-detail-a/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    @DisplayName("Detail page lists each application membership with its roles, and nested client memberships with their roles, and links through to the per-app and per-client member pages")
    void detailPageRendersAppAndNestedClientMembershipsWithRoles() throws Exception {
        Role appAdmin = roleRepository.save(new Role(null, appA.id(), "app-admin", null, LocalDateTime.now()));
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(),
            Set.of(new ApplicationMembershipRole(appAdmin.id()))
        ));
        clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(),
            Set.of(new ClientMembershipRole(viewer.id()))
        ));

        mockMvc.perform(get("/manage/t/user-detail-a/users/" + aliceA.id()).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Acme Web")))
            .andExpect(content().string(containsString("app-admin")))
            .andExpect(content().string(containsString("acme-spa")))
            .andExpect(content().string(containsString("viewer")))
            // Links through to the per-Application / per-Client members pages.
            .andExpect(content().string(containsString(
                "/manage/t/user-detail-a/applications/" + appA.id() + "/members"
            )))
            .andExpect(content().string(containsString(
                "/manage/t/user-detail-a/applications/" + appA.id()
                    + "/clients/" + clientA.registeredClientId() + "/members"
            )));
    }

    @Test
    @DisplayName("A user with no memberships renders an empty-state message")
    void detailPageShowsEmptyStateWhenUserHasNoMemberships() throws Exception {
        mockMvc.perform(get("/manage/t/user-detail-a/users/" + aliceA.id()).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No Memberships yet")));
    }

    @Test
    @DisplayName("Detail page is read-only — it has no edit/grant/delete buttons or membership-mutating forms")
    void detailPageHasNoEditOrGrantOrDeleteAffordances() throws Exception {
        Role viewer = roleRepository.save(new Role(null, appA.id(), "viewer", null, LocalDateTime.now()));
        ApplicationMembership am = appMembershipRepository.save(new ApplicationMembership(
            null, aliceA.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));
        clientMembershipRepository.save(new ClientMembership(
            null, aliceA.id(), clientA.id(), am.id(), LocalDateTime.now(), ownerA.id(),
            Set.of(new ClientMembershipRole(viewer.id()))
        ));

        String body = mockMvc.perform(get("/manage/t/user-detail-a/users/" + aliceA.id()).session(sessionA))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Read-only contract: no membership-mutating forms or links on this page.
        // (The global Sign out form in the page header posts to /manage/logout, not into the tenant URL space.)
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("/edit");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("/delete");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("action=\"/manage/t/");
    }

    @Test
    @DisplayName("A session for tenant B cannot view a user-detail page in tenant A — redirects to tenant A's login")
    void crossTenantSessionCannotReachUserDetail() throws Exception {
        userRepository.save(new User(null, tenantB.id(), "ownerB@example.test", passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/user-detail-b/login")
                .param("email", "ownerB@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        mockMvc.perform(get("/manage/t/user-detail-a/users/" + aliceA.id()).session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/user-detail-a/login"));
    }

    @Test
    @DisplayName("On the users list page, each email links to that user's detail page")
    void emailOnListPageLinksToDetail() throws Exception {
        mockMvc.perform(get("/manage/t/user-detail-a/users").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "/manage/t/user-detail-a/users/" + aliceA.id()
            )));
    }

    @Test
    @DisplayName("Empty-state still shows when other users in the same tenant have memberships but the target user does not")
    void detailPageRendersWhenMembershipsExistInOtherAppsButNotForThisUser() throws Exception {
        // Other user has memberships, target user does not — empty state still shows.
        User bob = userRepository.save(new User(null, tenantA.id(), "bob@example.test", passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));
        appMembershipRepository.save(new ApplicationMembership(
            null, bob.id(), appA.id(), LocalDateTime.now(), ownerA.id(), Set.of()
        ));

        mockMvc.perform(get("/manage/t/user-detail-a/users/" + aliceA.id()).session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No Memberships yet")))
            .andExpect(content().string(not(containsString("Acme Web"))));
    }
}

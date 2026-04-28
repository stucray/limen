package com.stucray.limen.management.applications;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
class ApplicationManagementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
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

        tenantA = tenantRepository.save(new Tenant(null, "corp-a", "Corp A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "corp-b", "Corp B", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(null, tenantA.id(), "owner", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp-a/login")
                .param("username", "owner").param("password", "pass").with(csrf()))
            .andReturn();
        sessionA = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void ownerCanCreateApplication() throws Exception {
        mockMvc.perform(post("/manage/t/corp-a/applications").session(sessionA).with(csrf())
                .param("name", "My App").param("description", "A test app"))
            .andExpect(status().is3xxRedirection());

        assertThat(applicationRepository.findAllByTenantId(tenantA.id()))
            .extracting(Application::name).containsExactly("My App");
    }

    @Test
    void ownerCanListApplications() throws Exception {
        applicationRepository.save(new Application(null, tenantA.id(), "App 1", "desc", LocalDateTime.now()));

        mockMvc.perform(get("/manage/t/corp-a/applications").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("App 1")));
    }

    @Test
    void ownerCanEditApplication() throws Exception {
        Application app = applicationRepository.save(new Application(null, tenantA.id(), "Old Name", "old desc", LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp-a/applications/" + app.id() + "/edit").session(sessionA).with(csrf())
                .param("name", "New Name").param("description", "new desc"))
            .andExpect(status().is3xxRedirection());

        assertThat(applicationRepository.findById(app.id()).orElseThrow().name()).isEqualTo("New Name");
    }

    @Test
    void ownerCanDeleteApplicationWithoutClients() throws Exception {
        Application app = applicationRepository.save(new Application(null, tenantA.id(), "App", null, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp-a/applications/" + app.id() + "/delete").session(sessionA).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(applicationRepository.findById(app.id())).isEmpty();
    }

    @Test
    void deleteBlockedWhenApplicationHasClients() throws Exception {
        Application app = applicationRepository.save(new Application(null, tenantA.id(), "App", null, LocalDateTime.now()));
        // Insert a fake client record to simulate the FK constraint check
        jdbcTemplate.update(
            "INSERT INTO oauth2_registered_client (id, client_id, client_id_issued_at, client_name, client_authentication_methods, authorization_grant_types, scopes, client_settings, token_settings, application_id) VALUES (?,?,NOW(),'test','client_secret_basic','authorization_code','openid','{}','{}',?)",
            "fake-client-id", "fake-client", app.id()
        );

        mockMvc.perform(post("/manage/t/corp-a/applications/" + app.id() + "/delete").session(sessionA).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Cannot delete")));

        assertThat(applicationRepository.findById(app.id())).isPresent();
    }

    @Test
    void tenantAApplicationsNotVisibleToTenantBSession() throws Exception {
        Application appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
        userRepository.save(new User(null, tenantB.id(), "ownerB", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        MvcResult loginB = mockMvc.perform(post("/manage/t/corp-b/login")
                .param("username", "ownerB").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        // TenantAccessFilter force-logs out the cross-tenant session and
        // redirects to the URL slug's login page (parent PRD #32 user story 7).
        mockMvc.perform(get("/manage/t/corp-a/applications").session(sessionB))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp-a/login"));
    }
}

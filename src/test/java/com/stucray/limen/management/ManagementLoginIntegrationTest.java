package com.stucray.limen.management;

import com.stucray.limen.TestcontainersConfiguration;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Management surface: login + tenant access control")
class ManagementLoginIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant testTenant;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM persistent_logins");

        testTenant = tenantRepository.save(new Tenant(
            null, "test-corp", "Test Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        userRepository.save(new User(
            null, testTenant.id(), "owner",
            passwordEncoder.encode("password"),
            true, false, false, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("GET /manage/t/{slug}/login renders the login page with the tenant's display name")
    void loginFormRendersForTenant() throws Exception {
        mockMvc.perform(get("/manage/t/test-corp/login"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Test Corp")));
    }

    @Test
    @DisplayName("Successful login redirects to /manage/t/{slug}/")
    void successfulLoginRedirectsToHome() throws Exception {
        mockMvc.perform(post("/manage/t/test-corp/login")
                .param("username", "owner")
                .param("password", "password")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/test-corp/"));
    }

    @Test
    @DisplayName("Bad password redirects back to /manage/t/{slug}/login?error")
    void failedLoginShowsError() throws Exception {
        mockMvc.perform(post("/manage/t/test-corp/login")
                .param("username", "owner")
                .param("password", "wrong")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/test-corp/login?error"));
    }

    @Test
    @DisplayName("Unauthenticated access to /manage/t/{slug}/ is redirected to that tenant's login page")
    void unauthenticatedAccessToManagementPageRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/manage/t/test-corp/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/test-corp/login"));
    }

    @Test
    @DisplayName("Authenticated user can GET /manage/t/{slug}/ and sees their tenant's display name")
    void authenticatedUserCanAccessManagementHome() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/manage/t/test-corp/login")
                .param("username", "owner")
                .param("password", "password")
                .with(csrf()))
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/manage/t/test-corp/").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Test Corp")));
    }

    @Test
    @DisplayName("System admin can sign in at /manage/t/system/login")
    void systemAdminCanLoginAtSystemSlug() throws Exception {
        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();
        userRepository.save(new User(
            null, systemTenant.id(), "sysadmin",
            passwordEncoder.encode("syspassword"),
            true, false, false, LocalDateTime.now()
        ));

        mockMvc.perform(post("/manage/t/system/login")
                .param("username", "sysadmin")
                .param("password", "syspassword")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/system/"));
    }

    @Test
    @DisplayName("Cross-tenant access: TenantAccessFilter force-logs out and redirects to the URL slug's login page")
    void userCannotAccessAnotherTenantManagementPages() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/manage/t/test-corp/login")
                .param("username", "owner")
                .param("password", "password")
                .with(csrf()))
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        Tenant otherTenant = tenantRepository.save(new Tenant(
            null, "other-corp", "Other Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));

        // TenantAccessFilter force-logs the cross-tenant session out and
        // redirects to the URL slug's login page (defence-in-depth, parent PRD #32 user story 7).
        mockMvc.perform(get("/manage/t/other-corp/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/other-corp/login"));
    }
}

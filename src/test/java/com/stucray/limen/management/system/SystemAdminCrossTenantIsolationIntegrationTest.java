package com.stucray.limen.management.system;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the security model where a system admin (authenticated to the `system` tenant)
 * cannot reach another tenant's management routes. The TenantAccessFilter is
 * expected to clear the security context and redirect to that tenant's login page.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("System admin cannot cross into other tenants' management routes")
class SystemAdminCrossTenantIsolationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    MockHttpSession sysadminSession;
    Tenant acme;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'system')");

        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();
        userRepository.save(new User(
            null, systemTenant.id(), "sysadmin",
            passwordEncoder.encode("syspass"),
            true, false, false, LocalDateTime.now()
        ));

        acme = tenantRepository.save(new Tenant(
            null, "acme", "Acme", TenantStatus.ACTIVE, LocalDateTime.now()
        ));

        MvcResult login = mockMvc.perform(post("/manage/t/system/login")
                .param("username", "sysadmin")
                .param("password", "syspass")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        sysadminSession = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(sysadminSession).isNotNull();
    }

    @Test
    @DisplayName("Sysadmin's session hitting /manage/t/acme/users is invalidated and redirected to /manage/t/acme/login")
    void systemAdminCannotReachAnotherTenantsManagementRoutes() throws Exception {
        mockMvc.perform(get("/manage/t/acme/users").session(sysadminSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/acme/login"));

        // Filter invalidates the session.
        assertThat(sysadminSession.isInvalid()).isTrue();

        // Same now-invalid session presented to /manage/t/system/ no longer authenticates.
        mockMvc.perform(get("/manage/t/system/").session(sysadminSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/system/login"));
    }
}

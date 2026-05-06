package com.stucray.limen.system;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("System admin: /manage/system/tenants/new — provision tenant + owner + verification email")
class SystemAdminTenantCreateIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    MockHttpSession sysadminSession;
    Tenant customerTenant;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute(
            "DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute(
            "DELETE FROM users WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'system')");

        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();
        userRepository.save(new User(
            null, systemTenant.id(), "sysadmin@example.test",
            passwordEncoder.encode("syspass"),
            true, false, false, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/system/login")
                .param("email", "sysadmin@example.test")
                .param("password", "syspass")
                .with(csrf()))
            .andReturn();
        sysadminSession = (MockHttpSession) login.getRequest().getSession(false);

        customerTenant = tenantRepository.save(new Tenant(
            null, "customer", "Customer", TenantStatus.ACTIVE, LocalDateTime.now()));
    }

    @Test
    @DisplayName("GET /manage/system/tenants/new renders the form for a system admin")
    void formRendersForSystemAdmin() throws Exception {
        mockMvc.perform(get("/manage/system/tenants/new").session(sysadminSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Create a tenant")));
    }

    @Test
    @DisplayName("Successful POST creates the tenant + owner user (emailVerified=false, mustChangePassword=true) and redirects to the tenants list")
    void successfulPostCreatesTenantAndOwner() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/new").session(sysadminSession)
                .param("slug", "newco")
                .param("displayName", "Newco Inc.")
                .param("ownerEmail", "owner@newco.test")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/system/tenants"));

        Tenant created = tenantRepository.findBySlug("newco").orElseThrow();
        assertThat(created.displayName()).isEqualTo("Newco Inc.");
        assertThat(created.status()).isEqualTo(TenantStatus.ACTIVE);

        User owner = userRepository.findByEmailAndTenantId("owner@newco.test", created.id()).orElseThrow();
        assertThat(owner.tenantOwner()).isTrue();
        assertThat(owner.emailVerified()).isFalse();
        assertThat(owner.mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Reserved slug ('system') is rejected and the form re-renders with the reserved-slug message")
    void reservedSlugReturnsFormError() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/new").session(sysadminSession)
                .param("slug", "system")
                .param("displayName", "X")
                .param("ownerEmail", "x@example.test")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("reserved")));
    }

    @Test
    @DisplayName("Duplicate slug is rejected and the form re-renders with the already-taken message")
    void duplicateSlugReturnsFormError() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/new").session(sysadminSession)
                .param("slug", "customer")
                .param("displayName", "X")
                .param("ownerEmail", "x@example.test")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already taken")));
    }

    @Test
    @DisplayName("Invalid email format is rejected")
    void invalidEmailReturnsFormError() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/new").session(sysadminSession)
                .param("slug", "fresh")
                .param("displayName", "Fresh")
                .param("ownerEmail", "not-an-email")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("valid email address")));
    }

    @Test
    @DisplayName("Tenant Owner (non-system-admin) gets 403 when accessing the form (read or write)")
    void tenantOwnerIsForbidden() throws Exception {
        userRepository.save(new User(
            null, customerTenant.id(), "owner@customer.test",
            passwordEncoder.encode("ownerpass"),
            true, false, true, true, LocalDateTime.now()));

        MvcResult ownerLogin = mockMvc.perform(post("/manage/t/customer/login")
                .param("email", "owner@customer.test")
                .param("password", "ownerpass")
                .with(csrf()))
            .andReturn();
        MockHttpSession ownerSession = (MockHttpSession) ownerLogin.getRequest().getSession(false);

        mockMvc.perform(get("/manage/system/tenants/new").session(ownerSession))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/manage/system/tenants/new").session(ownerSession)
                .param("slug", "x").param("displayName", "X").param("ownerEmail", "x@example.test")
                .with(csrf()))
            .andExpect(status().isForbidden());
    }
}

package com.stucray.limen.management.system;

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
class SystemAdminIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    MockHttpSession adminSession;
    Tenant customerTenant;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'system')");

        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();
        userRepository.save(new User(null, systemTenant.id(), "sysadmin", passwordEncoder.encode("syspass"), true, false, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/system/login")
                .param("username", "sysadmin").param("password", "syspass").with(csrf()))
            .andReturn();
        adminSession = (MockHttpSession) login.getRequest().getSession(false);

        customerTenant = tenantRepository.save(new Tenant(null, "customer", "Customer", TenantStatus.ACTIVE, LocalDateTime.now()));
    }

    @Test
    void adminCanListTenants() throws Exception {
        mockMvc.perform(get("/manage/system/tenants").session(adminSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("customer")));
    }

    @Test
    void adminCanSuspendTenant() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/" + customerTenant.id() + "/suspend").session(adminSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findById(customerTenant.id()).orElseThrow().status())
            .isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void suspendedTenantLoginIsBlocked() throws Exception {
        userRepository.save(new User(null, customerTenant.id(), "owner", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        tenantRepository.save(customerTenant.withStatus(TenantStatus.SUSPENDED));

        mockMvc.perform(post("/manage/t/customer/login")
                .param("username", "owner").param("password", "pass").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/customer/login?error"));
    }

    @Test
    void adminCanUnsuspendTenant() throws Exception {
        tenantRepository.save(customerTenant.withStatus(TenantStatus.SUSPENDED));

        mockMvc.perform(post("/manage/system/tenants/" + customerTenant.id() + "/unsuspend").session(adminSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findById(customerTenant.id()).orElseThrow().status())
            .isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void adminCanDeleteTenant() throws Exception {
        mockMvc.perform(post("/manage/system/tenants/" + customerTenant.id() + "/delete").session(adminSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findById(customerTenant.id())).isEmpty();
    }

    @Test
    void systemTenantCannotBeSuspended() throws Exception {
        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();

        mockMvc.perform(post("/manage/system/tenants/" + systemTenant.id() + "/suspend").session(adminSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findBySlug("system").orElseThrow().status())
            .isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void systemTenantCannotBeDeleted() throws Exception {
        Tenant systemTenant = tenantRepository.findBySlug("system").orElseThrow();

        mockMvc.perform(post("/manage/system/tenants/" + systemTenant.id() + "/delete").session(adminSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findBySlug("system")).isPresent();
    }

    @Test
    void tenantOwnerCanViewAndUpdateTenantDetails() throws Exception {
        Tenant corp = tenantRepository.save(new Tenant(null, "my-corp", "My Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(null, corp.id(), "owner", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));
        MvcResult login = mockMvc.perform(post("/manage/t/my-corp/login")
                .param("username", "owner").param("password", "pass").with(csrf()))
            .andReturn();
        MockHttpSession ownerSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/manage/t/my-corp/settings").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("My Corp")));

        mockMvc.perform(post("/manage/t/my-corp/settings/display-name").session(ownerSession).with(csrf())
                .param("displayName", "My Corp Renamed"))
            .andExpect(status().is3xxRedirection());

        assertThat(tenantRepository.findBySlug("my-corp").orElseThrow().displayName())
            .isEqualTo("My Corp Renamed");
    }
}

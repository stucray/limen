package com.stucray.limen.management.users;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserManagementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;
    MockHttpSession ownerSession;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenant = tenantRepository.save(new Tenant(null, "corp", "Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(null, tenant.id(), "owner", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("username", "owner").param("password", "pass").with(csrf()))
            .andReturn();
        ownerSession = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void ownerCanListUsers() throws Exception {
        mockMvc.perform(get("/manage/t/corp/users").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("owner")));
    }

    @Test
    void ownerCanCreateUser() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users").session(ownerSession).with(csrf())
                .param("username", "alice").param("temporaryPassword", "temppass1"))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findByUsernameAndTenantId("alice", tenant.id())).isPresent();
        assertThat(userRepository.findByUsernameAndTenantId("alice", tenant.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    void ownerCanDisableAndEnableUser() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/disable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isFalse();

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/enable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void ownerCanDeleteUser() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/delete").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id())).isEmpty();
    }

    @Test
    void ownerCanResetPassword() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice", passwordEncoder.encode("oldpass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/reset-password").session(ownerSession).with(csrf())
                .param("temporaryPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(alice.id()).orElseThrow();
        assertThat(updated.mustChangePassword()).isTrue();
    }

    @Test
    void ownerCanGrantAndRevokeTenantOwnerRole() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/grant-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isTrue();

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/revoke-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isFalse();
    }

    @Test
    void userWithMustChangePasswordIsInterceptedToChangePasswordPage() throws Exception {
        userRepository.save(new User(null, tenant.id(), "newuser", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("username", "newuser").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/manage/t/corp/").session(newUserSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp/change-password"));
    }

    @Test
    void mustChangePasswordClearedAfterChange() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("username", "newuser").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/manage/t/corp/change-password").session(newUserSession).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isFalse();
    }
}

package com.stucray.limen.management.users;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Tenant user management: list / create / disable / delete / reset-password / grant-owner + change-password flow")
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
        userRepository.save(new User(null, tenant.id(), "owner@example.test", passwordEncoder.encode("pass"), true, false, true, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", "owner@example.test").param("password", "pass").with(csrf()))
            .andReturn();
        ownerSession = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    @DisplayName("Tenant owner can GET /manage/t/{slug}/users and see the tenant's users")
    void ownerCanListUsers() throws Exception {
        mockMvc.perform(get("/manage/t/corp/users").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("owner@example.test")));
    }

    @Test
    @DisplayName("Creating a user persists them with mustChangePassword=true so they're forced to set a real password on first login")
    void ownerCanCreateUser() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users").session(ownerSession).with(csrf())
                .param("email", "alice@example.test").param("temporaryPassword", "temppass1"))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findByEmailAndTenantId("alice@example.test", tenant.id())).isPresent();
        assertThat(userRepository.findByEmailAndTenantId("alice@example.test", tenant.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Tenant owner can disable a user and re-enable them, flipping the enabled flag in both directions")
    void ownerCanDisableAndEnableUser() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/disable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isFalse();

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/enable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    @DisplayName("Tenant owner can delete a user — the row is removed from the users table")
    void ownerCanDeleteUser() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/delete").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id())).isEmpty();
    }

    @Test
    @DisplayName("Reset-password sets a temporary password and flips mustChangePassword=true so the user must change it on next login")
    void ownerCanResetPassword() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice@example.test", passwordEncoder.encode("oldpass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/reset-password").session(ownerSession).with(csrf())
                .param("temporaryPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(alice.id()).orElseThrow();
        assertThat(updated.mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Tenant owner can grant the tenant-owner role to another user and revoke it again")
    void ownerCanGrantAndRevokeTenantOwnerRole() throws Exception {
        User alice = userRepository.save(new User(null, tenant.id(), "alice@example.test", passwordEncoder.encode("pass"), true, false, false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/grant-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isTrue();

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/revoke-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isFalse();
    }

    @Test
    @DisplayName("A user with mustChangePassword=true is redirected to the change-password page on every authenticated request")
    void userWithMustChangePasswordIsInterceptedToChangePasswordPage() throws Exception {
        userRepository.save(new User(null, tenant.id(), "newuser@example.test", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", "newuser@example.test").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/manage/t/corp/").session(newUserSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp/change-password"));
    }

    @Test
    @DisplayName("After successfully changing the password, mustChangePassword is cleared so the user can use the app normally")
    void mustChangePasswordClearedAfterChange() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", "newuser@example.test").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/manage/t/corp/change-password").session(newUserSession).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("Mismatched new/confirm passwords re-render the form with an error and leave mustChangePassword=true")
    void changePasswordRedisplaysFormWhenNewAndConfirmDoNotMatch() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", "newuser@example.test").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/manage/t/corp/change-password").session(newUserSession).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "different1"))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("errorMessage", "Passwords do not match"));

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Blank/whitespace new password re-renders the form with 'Password is required' and leaves mustChangePassword=true")
    void changePasswordRedisplaysFormWhenNewPasswordIsBlank() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test", passwordEncoder.encode("temp"), true, true, false, LocalDateTime.now()));

        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", "newuser@example.test").param("password", "temp").with(csrf()))
            .andReturn();
        MockHttpSession newUserSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/manage/t/corp/change-password").session(newUserSession).with(csrf())
                .param("newPassword", "   ").param("confirmPassword", "   "))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("errorMessage", "Password is required"));

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("GET /manage/t/{slug}/change-password renders the form template with the tenant slug in the model")
    void changePasswordFormGetRendersTemplateWithSlug() throws Exception {
        mockMvc.perform(get("/manage/t/corp/change-password").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("slug", "corp"));
    }
}

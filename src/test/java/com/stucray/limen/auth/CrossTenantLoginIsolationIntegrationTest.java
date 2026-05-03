package com.stucray.limen.auth;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant credential isolation: an `alice` in tenant A must never be authenticatable
 * with tenant B's password (and vice-versa) on either login surface.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Cross-tenant credential isolation")
class CrossTenantLoginIsolationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        alpha = tenantRepository.save(new Tenant(null, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        beta = tenantRepository.save(new Tenant(null, "beta", "Beta", TenantStatus.ACTIVE, LocalDateTime.now()));

        userRepository.save(new User(
            null, alpha.id(), "alice@example.test",
            passwordEncoder.encode("alpha-pwd"),
            true, false, false, true, LocalDateTime.now()
        ));
        userRepository.save(new User(
            null, beta.id(), "alice@example.test",
            passwordEncoder.encode("beta-pwd"),
            true, false, false, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("OAuth2 surface: alice@alpha cannot sign in with beta's password")
    void oauth2LoginRejectsOtherTenantsPassword() throws Exception {
        mockMvc.perform(post("/t/alpha/login")
                .param("email", "alice@example.test")
                .param("password", "beta-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha/login?error"));
    }

    @Test
    @DisplayName("Management surface: alice@alpha cannot sign in with beta's password")
    void managementLoginRejectsOtherTenantsPassword() throws Exception {
        mockMvc.perform(post("/manage/t/alpha/login")
                .param("email", "alice@example.test")
                .param("password", "beta-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/alpha/login?error"));
    }

    @Test
    @DisplayName("OAuth2 surface: alice@alpha signs in successfully with alpha's password")
    void oauth2LoginAcceptsOwnTenantsPassword() throws Exception {
        mockMvc.perform(post("/t/alpha/login")
                .param("email", "alice@example.test")
                .param("password", "alpha-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha/"));
    }
}

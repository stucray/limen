package com.stucray.limen;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bare /login is no longer a POST target — it 302s to the landing page (or to a
 * specific tenant's management login when given a {@code ?slug=} query parameter).
 * The root path {@code /} renders the landing page directly. Authentication itself
 * is exercised by {@link com.stucray.limen.management.ManagementLoginIntegrationTest}
 * (management surface) and by {@code TenantOAuth2RoutingIntegrationTest} /
 * {@code OAuth2ForcedPasswordChangeIntegrationTest} (OAuth2 surface).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Long systemTenantId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'system')");

        systemTenantId = tenantRepository.findBySlug("system").orElseThrow().id();
        userRepository.save(new User(null, systemTenantId, "testuser", passwordEncoder.encode("password"), true, false, false, LocalDateTime.now()));
    }

    @Test
    void bareLoginRedirectsToLanding() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    void loginWithSlugRedirectsToTenantLogin() throws Exception {
        mockMvc.perform(get("/login").param("slug", "acme"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/acme/login"));
    }

    @Test
    void rootRendersLandingPage() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in to your organization")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Create a new organization")));
    }

    @Test
    void systemAdminLoginAtSystemTenantSucceeds() throws Exception {
        mockMvc.perform(post("/manage/t/system/login")
                .param("username", "testuser")
                .param("password", "password")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/system/"));
    }
}

package com.stucray.limen;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
        // Delete non-system users to avoid cross-test pollution while preserving the system tenant
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'system')");

        systemTenantId = tenantRepository.findBySlug("system").orElseThrow().id();
        userRepository.save(new User(null, systemTenantId, "testuser", passwordEncoder.encode("password"), true, false, false, LocalDateTime.now()));
    }

    @Test
    void loginFormRendersForUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")));
    }

    @Test
    void successfulLoginRedirects() throws Exception {
        mockMvc.perform(post("/login")
                .param("username", "testuser")
                .param("password", "password")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void logoutClearsCookies() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login")
                .param("username", "testuser")
                .param("password", "password")
                .with(csrf()))
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        MvcResult logoutResult = mockMvc.perform(post("/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(logoutResult.getResponse().getCookies())
            .extracting(Cookie::getName)
            .contains("JSESSIONID", "remember-me");
    }

    @Test
    void rememberMeTokenPersistedOnLogin() throws Exception {
        mockMvc.perform(post("/login")
                .param("username", "testuser")
                .param("password", "password")
                .param("remember-me", "on")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(cookie().exists("remember-me"));

        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM persistent_logins WHERE username = 'testuser'", Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void rememberMeReauthenticatesWithoutSession() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login")
                .param("username", "testuser")
                .param("password", "password")
                .param("remember-me", "on")
                .with(csrf()))
            .andReturn();

        Cookie rememberMeCookie = loginResult.getResponse().getCookie("remember-me");
        assertThat(rememberMeCookie).isNotNull();

        String tokenBefore = jdbcTemplate.queryForObject(
            "SELECT token FROM persistent_logins WHERE username = 'testuser'", String.class);

        MvcResult reAuthResult = mockMvc.perform(get("/login").cookie(rememberMeCookie))
            .andReturn();

        assertThat(reAuthResult.getResponse().getStatus()).isNotEqualTo(302);

        String tokenAfter = jdbcTemplate.queryForObject(
            "SELECT token FROM persistent_logins WHERE username = 'testuser'", String.class);
        assertThat(tokenAfter).isNotEqualTo(tokenBefore);
    }
}

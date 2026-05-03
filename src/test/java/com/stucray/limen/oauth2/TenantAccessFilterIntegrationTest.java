package com.stucray.limen.oauth2;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TenantAccessFilter forces logout when a session principal's tenant slug differs
 * from the URL slug. Asserts that a session for tenant A presented at tenant B's URL
 * is invalidated and redirected to B's login.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("TenantAccessFilter: a session whose principal belongs to a different tenant than the URL slug is force-logged-out")
class TenantAccessFilterIntegrationTest {

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
            null, alpha.id(), "owner@example.test",
            passwordEncoder.encode("alpha-pwd"),
            true, false, false, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("Alpha-tenant session presented at /manage/t/beta/ is invalidated, redirected to beta's login, and is no longer authenticated for alpha either")
    void crossTenantManagementAccessForcesLogoutAndRedirect() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/manage/t/alpha/login")
                .param("email", "owner@example.test")
                .param("password", "alpha-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/manage/t/beta/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/beta/login"));

        // The filter invalidates the session — `MockHttpSession.isInvalid()` reflects that.
        assertThat(session.isInvalid()).isTrue();

        // And the same session, presented on alpha's home page, no longer authenticates:
        // an unauthenticated request to /manage/t/alpha/ would normally redirect to login.
        mockMvc.perform(get("/manage/t/alpha/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/alpha/login"));
    }
}

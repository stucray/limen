package com.stucray.limen.auth;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

/**
 * End-to-end remember-me tests asserting tenant isolation at storage and at
 * cookie decode time. Asserts external behaviour (HTTP responses, cookie
 * presence/format, persisted row contents); avoids inspecting filter mechanics.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantPersistentTokenBasedRememberMeServicesIntegrationTest {

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

        alpha = tenantRepository.save(new Tenant(null, "alpha-rm", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        beta = tenantRepository.save(new Tenant(null, "beta-rm", "Beta", TenantStatus.ACTIVE, LocalDateTime.now()));

        userRepository.save(new User(null, alpha.id(), "alice",
            passwordEncoder.encode("alpha-pwd"), true, false, false, LocalDateTime.now()));
        userRepository.save(new User(null, beta.id(), "alice",
            passwordEncoder.encode("beta-pwd"), true, false, false, LocalDateTime.now()));
    }

    @Test
    void rememberMeRowOnOAuth2LoginCarriesTenantId() throws Exception {
        mockMvc.perform(post("/t/alpha-rm/login")
                .param("username", "alice")
                .param("password", "alpha-pwd")
                .param("remember-me", "on")
                .with(csrf()))
            .andExpect(cookie().exists("remember-me"));

        Long persistedTenantId = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM persistent_logins WHERE username = ?",
            Long.class, "alice"
        );
        assertThat(persistedTenantId).isEqualTo(alpha.id());

        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM persistent_logins WHERE tenant_id = ?",
            Integer.class, alpha.id()
        );
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void rememberMeOnManagementLoginCarriesTenantId() throws Exception {
        mockMvc.perform(post("/manage/t/alpha-rm/login")
                .param("username", "alice")
                .param("password", "alpha-pwd")
                .param("remember-me", "on")
                .with(csrf()))
            .andExpect(cookie().exists("remember-me"));

        Long persistedTenantId = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM persistent_logins WHERE username = ?",
            Long.class, "alice"
        );
        assertThat(persistedTenantId).isEqualTo(alpha.id());
    }

    @Test
    void cookieValueIncludesSlugAsThirdSegment() throws Exception {
        MvcResult result = mockMvc.perform(post("/t/alpha-rm/login")
                .param("username", "alice")
                .param("password", "alpha-pwd")
                .param("remember-me", "on")
                .with(csrf()))
            .andExpect(cookie().exists("remember-me"))
            .andReturn();

        Cookie rememberMeCookie = result.getResponse().getCookie("remember-me");
        assertThat(rememberMeCookie).isNotNull();

        // Cookie value is base64'd `series:token:slug` per AbstractRememberMeServices.
        String decoded = new String(java.util.Base64.getDecoder().decode(rememberMeCookie.getValue()));
        String[] parts = decoded.split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEqualTo("alpha-rm");
    }
}

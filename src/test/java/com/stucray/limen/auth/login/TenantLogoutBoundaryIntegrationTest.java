package com.stucray.limen.auth.login;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import jakarta.servlet.http.Cookie;
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
 * Boundary test for {@link TenantLogin#applyLogoutTo}. Exercises both surfaces
 * (OAuth2 end-user + management) through the public Spring Security pipeline,
 * asserting on observable HTTP outcomes (redirect targets, cookie clearing,
 * session invalidation) — not on internal handler structure — so the tests
 * survive any future re-extraction into a separate {@code TenantLogout} module.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("TenantLogin.applyLogoutTo: post-logout pipeline across surfaces")
class TenantLogoutBoundaryIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        alpha = tenantRepository.save(new Tenant(
            null, "alpha-lo", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(
            null, alpha.id(), "alice@example.test",
            passwordEncoder.encode("alpha-pwd"),
            true, false, false, true, LocalDateTime.now()));
    }

    @Test
    @DisplayName("OAuth2 surface: POST /t/{slug}/logout redirects to /t/{slug}/login (slug from request URI)")
    void oauth2LogoutRedirectsToTenantLogin() throws Exception {
        MockHttpSession session = loginThroughOAuth2();

        mockMvc.perform(post("/t/alpha-lo/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha-lo/login"));
    }

    @Test
    @DisplayName("Management surface: POST /manage/logout with matching Referer redirects to /manage/t/{slug}/login")
    void managementLogoutWithRefererRedirectsToTenantLogin() throws Exception {
        MockHttpSession session = loginThroughManagement();

        mockMvc.perform(post("/manage/logout")
                .session(session)
                .header("Referer", "http://localhost/manage/t/alpha-lo/applications")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/alpha-lo/login"));
    }

    @Test
    @DisplayName("Management surface: POST /manage/logout with no Referer falls back to /manage/t/system/login")
    void managementLogoutWithoutRefererFallsBackToSystemLogin() throws Exception {
        MockHttpSession session = loginThroughManagement();

        mockMvc.perform(post("/manage/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/system/login"));
    }

    @Test
    @DisplayName("Management surface: POST /manage/logout with a cross-site Referer falls back to /manage/t/system/login")
    void managementLogoutWithCrossSiteRefererFallsBackToSystemLogin() throws Exception {
        MockHttpSession session = loginThroughManagement();

        mockMvc.perform(post("/manage/logout")
                .session(session)
                .header("Referer", "https://evil.example.com/some/path")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/system/login"));
    }

    @Test
    @DisplayName("After logout: session is invalidated and JSESSIONID + remember-me cookies are cleared (Max-Age=0)")
    void logoutClearsSessionAndCookies() throws Exception {
        MockHttpSession session = loginThroughOAuth2();

        MvcResult logout = mockMvc.perform(post("/t/alpha-lo/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(session.isInvalid()).isTrue();

        Cookie jsession = logout.getResponse().getCookie("JSESSIONID");
        assertThat(jsession).as("JSESSIONID cookie cleared").isNotNull();
        assertThat(jsession.getMaxAge()).isZero();

        Cookie rememberMe = logout.getResponse().getCookie("remember-me");
        assertThat(rememberMe).as("remember-me cookie cleared").isNotNull();
        assertThat(rememberMe.getMaxAge()).isZero();
    }

    @Test
    @DisplayName("After logout: the previously-authenticated session can no longer access a protected page")
    void logoutClearsAuthentication() throws Exception {
        MockHttpSession session = loginThroughOAuth2();

        // Sanity: the session is authenticated before logout. /t/{slug}/ bounces
        // authed owners to the management home (issue #283), which is itself a
        // protected URL — so this hop also implicitly confirms cross-surface
        // auth carries the same session.
        mockMvc.perform(get("/t/alpha-lo/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/alpha-lo/"));

        mockMvc.perform(post("/t/alpha-lo/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        // Same session no longer authenticates; protected URL bounces back to login.
        mockMvc.perform(get("/t/alpha-lo/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha-lo/login"));
    }

    private MockHttpSession loginThroughOAuth2() throws Exception {
        MvcResult result = mockMvc.perform(post("/t/alpha-lo/login")
                .param("email", "alice@example.test")
                .param("password", "alpha-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private MockHttpSession loginThroughManagement() throws Exception {
        MvcResult result = mockMvc.perform(post("/manage/t/alpha-lo/login")
                .param("email", "alice@example.test")
                .param("password", "alpha-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }
}

package com.stucray.limen.enduser;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("End-user surface: post-login home page at /t/{slug}/")
class EndUserHomeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant testTenant;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM persistent_logins");

        testTenant = tenantRepository.save(new Tenant(
            null, "alpha-corp", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        userRepository.save(new User(
            null, testTenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("Successful end-user login redirects to /t/{slug}/")
    void successfulLoginRedirectsToHome() throws Exception {
        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test")
                .param("password", "password")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha-corp/"));
    }

    @Test
    @DisplayName("Unauthenticated GET /t/{slug}/ is redirected to that tenant's login page")
    void unauthenticatedAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/t/alpha-corp/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/alpha-corp/login"));
    }

    @Test
    @DisplayName("Authenticated end user can GET /t/{slug}/ and sees the tenant display name plus their email")
    void authenticatedUserCanAccessHome() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test")
                .param("password", "password")
                .with(csrf()))
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/t/alpha-corp/").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Alpha Corp")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@example.test")));
    }

    @Test
    @DisplayName("Cross-tenant access: TenantAccessFilter force-logs out and redirects to the URL slug's login page")
    void crossTenantAccessIsBlocked() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test")
                .param("password", "password")
                .with(csrf()))
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        tenantRepository.save(new Tenant(
            null, "beta-corp", "Beta Corp", TenantStatus.ACTIVE, LocalDateTime.now()
        ));

        mockMvc.perform(get("/t/beta-corp/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/t/beta-corp/login"));
    }

}

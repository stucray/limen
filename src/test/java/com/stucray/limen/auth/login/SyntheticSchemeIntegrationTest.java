package com.stucray.limen.auth.login;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 3 of #67. Registers a synthetic third {@link TenantUrlScheme} via
 * {@link TestConfiguration} and proves that cross-tenant force-logout fires
 * for URLs matching the new surface — i.e. that {@link com.stucray.limen.oauth2.TenantAccessFilter}
 * picks up newly-registered schemes through bean discovery without code
 * changes anywhere else.
 */
@Import({TestcontainersConfiguration.class, SyntheticSchemeIntegrationTest.SyntheticSchemeConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class SyntheticSchemeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationContext applicationContext;

    Tenant alpha;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        alpha = tenantRepository.save(new Tenant(
            null, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantRepository.save(new Tenant(
            null, "beta", "Beta", TenantStatus.ACTIVE, LocalDateTime.now()));
        userRepository.save(new User(
            null, alpha.id(), "owner",
            passwordEncoder.encode("alpha-pwd"),
            true, false, false, LocalDateTime.now()));
    }

    @Test
    void threeSchemesAreRegistered() {
        // Pin discoverability: the synthetic scheme is visible alongside the two defaults.
        assertThat(applicationContext.getBeansOfType(TenantUrlScheme.class)).hasSize(3);
    }

    @Test
    void crossTenantAccessOnSyntheticSurfaceForcesLogoutAndRedirects() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/manage/t/alpha/login")
                .param("username", "owner")
                .param("password", "alpha-pwd")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.isInvalid()).isFalse();

        // alpha's session presented at the synthetic surface for tenant beta — TenantAccessFilter
        // recognises /api/t/beta/... via the test-registered scheme and force-logs out.
        mockMvc.perform(get("/api/t/beta/data").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/api/t/beta/login"));

        assertThat(session.isInvalid()).isTrue();
    }

    @TestConfiguration
    static class SyntheticSchemeConfig {

        @Bean
        public TenantUrlScheme syntheticUrlScheme() {
            return new TenantUrlScheme(
                "synthetic",
                HttpMethod.POST, "/api/t/*/login",
                Pattern.compile("^/api/t/([^/]+)(?:/.*)?$"),
                "/api/t/{slug}/login",
                "/api/t/{slug}/",
                "/api/t/{slug}/change-password"
            );
        }

        @Bean
        @Order(0) // sit ahead of the OAuth2 (Order 1) and management (Order 2) chains
        public SecurityFilterChain syntheticFilterChain(
            HttpSecurity http,
            TenantLogin login,
            @Qualifier("syntheticUrlScheme") TenantUrlScheme scheme
        ) throws Exception {
            login.applyTo(http, scheme);
            http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/t/*/login").permitAll()
                    .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable());
            return http.build();
        }
    }
}

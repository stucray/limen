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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the deliberate design that {@code PostLoginIntents.tenantHome()} is the
 * terminal post-login intent on the {@code /t/**} surface. The intent chain in
 * {@code PostLoginIntents} only resumes URLs containing {@code /oauth2/authorize}
 * (line 87 guard on {@code resumeOAuth2Authorize}); every other saved request
 * — saved by {@code OAuth2LoginSecurityConfig}'s {@code HttpSessionRequestCache}
 * when an unauthenticated user hits any authenticated-only resource — falls
 * through to {@code tenantHome()}. There is no
 * {@code SavedRequestAwareAuthenticationSuccessHandler} wired before or after
 * the intent chain in {@code TenantLogin#filter}, so the design is genuinely
 * "tenantHome wins for everything that isn't OAuth2 authorize."
 *
 * <p>This is an intentional product decision (a user bouncing through login
 * lands at the tenant home, not back at the deep link they hit), but it was
 * undocumented and unpinned. A refactor that broadened the resume — or wired
 * a stock {@code SavedRequestAwareAuthenticationSuccessHandler} after the
 * intent chain — would silently change post-login UX. This test surfaces that
 * refactor before it ships.
 *
 * <p>The complementary positive case (a saved {@code /oauth2/authorize} URL
 * DOES resume) is pinned by {@code OAuth2ForcedPasswordChangeIntegrationTest}
 * and {@code OAuth2ConsentResumeIntegrationTest}; the cross-chain isolation
 * variant is pinned by {@code OAuth2NoConsentResumeIntegrationTest} on PR #287.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Terminal tenantHome contract: non-/oauth2/authorize saved requests on the /t/** surface do not resume post-login")
class TenantSavedRequestResumeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client WHERE application_id IS NOT NULL");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id != (SELECT id FROM tenants WHERE slug = 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        jdbcTemplate.execute("DELETE FROM persistent_logins");

        tenant = tenantRepository.save(new Tenant(
            null, "alpha-corp", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
    }

    @Test
    @DisplayName("Unauthenticated GET on a protected /t/{slug}/ resource (e.g. /change-password) 302s through the entry point to /t/{slug}/login")
    void unauthenticatedGetOnProtectedTenantResourceBouncesToLogin() throws Exception {
        // /t/{slug}/change-password is on the /t/** chain (OAuth2LoginSecurityConfig)
        // and not in the chain's permitAll list, so the
        // TenantLoginUrlAuthenticationEntryPoint redirects unauthenticated requests
        // to /t/{slug}/login. This pins the bounce-to-login half of the contract;
        // the post-login destination is asserted by the sibling test below.
        MvcResult result = mockMvc.perform(get("/t/alpha-corp/change-password"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getHeader("Location"))
            .as("entry point must redirect to the tenant's login page")
            .endsWith("/t/alpha-corp/login");
    }

    @Test
    @DisplayName("After bouncing off a non-/oauth2/authorize protected resource, post-login Location is /t/{slug}/ (terminal tenantHome) — the originally-requested URL is intentionally not resumed")
    void loginAfterBounceLandsAtTenantHomeNotResumedUrl() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("password"),
            true, false, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        // Unauthenticated hit on a protected /t/** URL → SavedRequest stored in the
        // chain's HttpSessionRequestCache and redirect to login.
        mockMvc.perform(get("/t/alpha-corp/change-password").session(session))
            .andExpect(status().is3xxRedirection());

        MvcResult loginResult = mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        // The post-login intent chain (TenantLogin#IntentChainSuccessHandler) only
        // consults resumeOAuth2Authorize, which gates on the SavedRequest URL
        // containing "/oauth2/authorize" (PostLoginIntents line 87). The saved
        // /change-password URL fails that gate, no other intent inspects the
        // SavedRequest, no SavedRequestAwareAuthenticationSuccessHandler is
        // wired — so tenantHome() is the terminal default and the user lands
        // at /t/{slug}/, not back at /change-password.
        assertThat(loginResult.getResponse().getHeader("Location"))
            .as("non-/oauth2/authorize saved requests must fall through to tenantHome; "
                + "broadening this is a deliberate product decision, not an oversight")
            .isEqualTo("/t/alpha-corp/");
    }
}

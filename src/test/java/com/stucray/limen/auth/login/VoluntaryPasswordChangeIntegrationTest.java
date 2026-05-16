package com.stucray.limen.auth.login;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.TenantUserDetails;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage for the voluntary (non-forced) password-change path through
 * {@link TenantPasswordChangeFlow}. The forced path is covered by
 * {@code OAuth2ForcedPasswordChangeIntegrationTest} and
 * {@code ManagementForcedPasswordChangeIntegrationTest}; both surfaces share
 * the same flow but enter through a different post-login intent
 * ({@code passwordChangeRequired()} vs. a logged-in user navigating directly
 * to {@code /change-password}). Until this slice, the voluntary path was only
 * exercised as a side effect of {@code PasswordResetFlowIntegrationTest}'s
 * second-POST refresh-protection test — never on its own happy paths.
 *
 * <p>The {@code TenantPasswordChangeFlow.changeAndRedirect} rotation is
 * unconditional (it runs regardless of the prior {@code mustChangePassword}
 * value), so the voluntary path also needs the session's stored principal
 * rebuilt with the new password hash — otherwise the next request runs against
 * a stale principal whose {@code getPassword()} no longer matches the DB.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Voluntary (non-forced) password change rotates the principal and lands at the tenant/management home on both surfaces")
class VoluntaryPasswordChangeIntegrationTest {

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
    @DisplayName("Logged-in user (mustChangePassword=false) voluntarily POSTs /t/{slug}/change-password — Location is /t/{slug}/ and the principal is rotated to the new hash")
    void voluntaryChangeOnTenantSurfaceRotatesPrincipalAndRedirectsToTenantHome() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("old-password"),
            true, false, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "old-password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(post("/t/alpha-corp/change-password")
                .param("newPassword", "new-password-123")
                .param("confirmPassword", "new-password-123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/t/alpha-corp/");
        assertPrincipalRotatedWithNewPassword(session, "new-password-123");
    }

    @Test
    @DisplayName("Logged-in user (mustChangePassword=false) voluntarily POSTs /manage/t/{slug}/change-password — Location is /manage/t/{slug}/ and the principal is rotated to the new hash")
    void voluntaryChangeOnManagementSurfaceRotatesPrincipalAndRedirectsToManagementHome() throws Exception {
        userRepository.save(new User(
            null, tenant.id(), "alice@example.test",
            passwordEncoder.encode("old-password"),
            true, false, false, true, LocalDateTime.now()));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/manage/t/alpha-corp/login")
                .param("email", "alice@example.test").param("password", "old-password")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection());

        MvcResult result = mockMvc.perform(post("/manage/t/alpha-corp/change-password")
                .param("newPassword", "new-password-123")
                .param("confirmPassword", "new-password-123")
                .session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/manage/t/alpha-corp/");
        assertPrincipalRotatedWithNewPassword(session, "new-password-123");
    }

    private void assertPrincipalRotatedWithNewPassword(MockHttpSession session, String newPassword) {
        // Voluntary change goes through the same TenantPasswordChangeFlow.changeAndRedirect
        // as the forced path, so the same unconditional SecurityContext rotation must
        // fire. Without it the session keeps a TenantUserDetails whose getPassword()
        // is the old hash, and subsequent request authentication breaks. Asserting
        // the new hash matches proves rotation rebuilt UserDetails from the saved
        // User row.
        SecurityContext stored = (SecurityContext) session.getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored).as("session must hold a rotated SecurityContext post-change").isNotNull();
        assertThat(stored.getAuthentication().getPrincipal())
            .isInstanceOfSatisfying(TenantUserDetails.class, refreshed -> {
                assertThat(refreshed.mustChangePassword())
                    .as("voluntary change must leave mustChangePassword=false on the rotated principal")
                    .isFalse();
                assertThat(passwordEncoder.matches(newPassword, refreshed.getPassword()))
                    .as("rotated principal must carry the new password hash")
                    .isTrue();
            });
    }
}

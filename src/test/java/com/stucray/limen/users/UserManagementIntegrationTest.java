package com.stucray.limen.users;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Tenant user administration: invariants enforced; audit events emitted; self-service change-password preserved")
class UserManagementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserAdministrationService userAdministration;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;
    User owner;
    MockHttpSession ownerSession;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");

        tenant = tenantRepository.save(new Tenant(null, "corp", "Corp", TenantStatus.ACTIVE, LocalDateTime.now()));
        owner = userRepository.save(new User(null, tenant.id(), "owner@example.test",
            passwordEncoder.encode("pass"), true, false, true, true, LocalDateTime.now()));

        ownerSession = loginAs("owner@example.test", "pass");
    }

    // --- list / detail ---

    @Test
    @DisplayName("Tenant owner can GET /manage/t/{slug}/users and see the tenant's users")
    void ownerCanListUsers() throws Exception {
        mockMvc.perform(get("/manage/t/corp/users").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("owner@example.test")));
    }

    // --- createUser ---

    @Test
    @DisplayName("Creating a user persists them with mustChangePassword=true and emits user_created audit row")
    void ownerCanCreateUser() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users").session(ownerSession).with(csrf())
                .param("email", "alice@example.test").param("temporaryPassword", "temppass1"))
            .andExpect(status().is3xxRedirection());

        User alice = userRepository.findByEmailAndTenantId("alice@example.test", tenant.id()).orElseThrow();
        assertThat(alice.mustChangePassword()).isTrue();
        assertAuditRow(tenant.id(), "user_created", owner.id(), alice.id(), "alice@example.test");
    }

    // --- enable / disable ---

    @Test
    @DisplayName("Disabling then re-enabling a user flips the flag both ways and emits user_disabled / user_enabled audit rows")
    void ownerCanDisableAndEnableUser() throws Exception {
        User alice = seedUser("alice@example.test", false, false);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/disable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isFalse();
        assertAuditRow(tenant.id(), "user_disabled", owner.id(), alice.id(), "alice@example.test");

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/enable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(alice.id()).orElseThrow().enabled()).isTrue();
        assertAuditRow(tenant.id(), "user_enabled", owner.id(), alice.id(), "alice@example.test");
    }

    @Test
    @DisplayName("An owner cannot disable their own account — would lock the actor out of undoing it")
    void ownerCannotDisableSelf() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users/" + owner.id() + "/disable").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp/users"))
            .andExpect(flash().attribute("errorMessage", "You cannot disable your own account"));

        assertThat(userRepository.findById(owner.id()).orElseThrow().enabled()).isTrue();
        assertNoAuditRow(tenant.id(), "user_disabled");
    }

    @Test
    @DisplayName("Service-level: disabling the last enabled tenant-owner is rejected — would orphan the tenant")
    void serviceLevelDisableLastEnabledOwnerRejected() {
        // owner is the only enabled tenant-owner. A *different* enabled non-owner is the actor.
        User actor = userRepository.save(new User(null, tenant.id(), "actor@example.test",
            passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));

        assertThatThrownBy(() -> userAdministration.disable(owner.id(), tenant.id(), actor.id()))
            .isInstanceOf(UserAdminException.WouldOrphanTenant.class)
            .hasMessageContaining("last enabled tenant owner");

        assertThat(userRepository.findById(owner.id()).orElseThrow().enabled()).isTrue();
        assertNoAuditRow(tenant.id(), "user_disabled");
    }

    @Test
    @DisplayName("Service-level: enable on an already-enabled user is idempotent — no row mutation, no event emitted")
    void enableIsIdempotent() {
        User alice = seedUser("alice@example.test", false, false); // enabled by default

        userAdministration.enable(alice.id(), tenant.id(), owner.id());

        assertThat(countAuditRows(tenant.id(), "user_enabled", alice.id())).isZero();
    }

    // --- delete ---

    @Test
    @DisplayName("Tenant owner can delete a user — the row is removed and a user_deleted audit row is written")
    void ownerCanDeleteUser() throws Exception {
        User alice = seedUser("alice@example.test", false, false);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/delete").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(alice.id())).isEmpty();
        assertAuditRow(tenant.id(), "user_deleted", owner.id(), alice.id(), "alice@example.test");
    }

    @Test
    @DisplayName("An owner cannot delete their own account")
    void ownerCannotDeleteSelf() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users/" + owner.id() + "/delete").session(ownerSession).with(csrf()))
            .andExpect(flash().attribute("errorMessage", "You cannot delete your own account"));

        assertThat(userRepository.findById(owner.id())).isPresent();
        assertNoAuditRow(tenant.id(), "user_deleted");
    }

    @Test
    @DisplayName("Service-level: deleting the last enabled tenant-owner is rejected — would orphan the tenant")
    void serviceLevelDeleteLastEnabledOwnerRejected() {
        User actor = userRepository.save(new User(null, tenant.id(), "actor@example.test",
            passwordEncoder.encode("pass"), true, false, false, true, LocalDateTime.now()));

        assertThatThrownBy(() -> userAdministration.deleteUser(owner.id(), tenant.id(), actor.id()))
            .isInstanceOf(UserAdminException.WouldOrphanTenant.class)
            .hasMessageContaining("last enabled tenant owner");

        assertThat(userRepository.findById(owner.id())).isPresent();
    }

    // --- resetPassword ---

    @Test
    @DisplayName("Reset-password sets a temporary password, flips mustChangePassword=true, and emits a password_changed row with trigger=admin_reset")
    void ownerCanResetPassword() throws Exception {
        User alice = seedUser("alice@example.test", false, false);
        userRepository.save(alice.withMustChangePassword(false));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/reset-password").session(ownerSession).with(csrf())
                .param("temporaryPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(alice.id()).orElseThrow();
        assertThat(updated.mustChangePassword()).isTrue();
        Map<String, Object> row = latestEventOfType(tenant.id(), "password_changed");
        assertThat(row.get("details").toString().replace(" ", "")).contains("\"trigger\":\"admin_reset\"");
    }

    @Test
    @DisplayName("Reset-password on a locked user clears the lockout state atomically AND emits both password_changed and account_unlocked rows")
    void resetPasswordOnLockedUserClearsLockout() throws Exception {
        User alice = seedUser("alice@example.test", false, false);
        userRepository.save(alice.withFailedLoginAttempts(5)
            .withLockedUntil(LocalDateTime.now().plusMinutes(10)));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/reset-password").session(ownerSession).with(csrf())
                .param("temporaryPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(alice.id()).orElseThrow();
        assertThat(updated.failedLoginAttempts()).isZero();
        assertThat(updated.lockedUntil()).isNull();
        assertAuditRowExists(tenant.id(), "password_changed", alice.id());
        assertAuditRowExists(tenant.id(), "account_unlocked", alice.id());
    }

    @Test
    @DisplayName("Reset-password on an un-locked user emits only password_changed (no spurious account_unlocked event)")
    void resetPasswordOnUnlockedUserDoesNotEmitUnlocked() throws Exception {
        User alice = seedUser("alice@example.test", false, false);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/reset-password").session(ownerSession).with(csrf())
                .param("temporaryPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        assertAuditRowExists(tenant.id(), "password_changed", alice.id());
        assertThat(countAuditRows(tenant.id(), "account_unlocked", alice.id())).isZero();
    }

    // --- grant / revoke owner ---

    @Test
    @DisplayName("Granting tenant-owner role flips the flag and emits tenant_ownership_granted")
    void ownerCanGrantTenantOwner() throws Exception {
        User alice = seedUser("alice@example.test", false, false);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/grant-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isTrue();
        assertAuditRow(tenant.id(), "tenant_ownership_granted", owner.id(), alice.id(), "alice@example.test");
    }

    @Test
    @DisplayName("Revoking tenant-owner role flips the flag and emits tenant_ownership_revoked — when not the last enabled owner")
    void ownerCanRevokeTenantOwner() throws Exception {
        // owner + alice are both enabled tenant-owners → revoking alice leaves owner as the sole one. Allowed.
        User alice = seedUser("alice@example.test", false, true);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/revoke-owner").session(ownerSession).with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isFalse();
        assertAuditRow(tenant.id(), "tenant_ownership_revoked", owner.id(), alice.id(), "alice@example.test");
    }

    @Test
    @DisplayName("Cannot grant tenant-owner to a disabled user — TargetNotEligible")
    void cannotGrantOwnerToDisabledUser() throws Exception {
        User alice = seedUser("alice@example.test", true, false);

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/grant-owner").session(ownerSession).with(csrf()))
            .andExpect(flash().attribute("errorMessage", "Cannot grant ownership to a disabled user"));

        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isFalse();
        assertNoAuditRow(tenant.id(), "tenant_ownership_granted");
    }

    @Test
    @DisplayName("Cannot grant tenant-owner to a user with unverified email — TargetNotEligible")
    void cannotGrantOwnerToUnverifiedUser() throws Exception {
        User alice = userRepository.save(new User(
            null, tenant.id(), "alice@example.test", passwordEncoder.encode("pass"),
            true, false, false, /* emailVerified */ false, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + alice.id() + "/grant-owner").session(ownerSession).with(csrf()))
            .andExpect(flash().attribute("errorMessage", "Cannot grant ownership to a user with an unverified email"));

        assertThat(userRepository.findById(alice.id()).orElseThrow().tenantOwner()).isFalse();
    }

    @Test
    @DisplayName("Owner cannot revoke their own ownership — CannotTargetSelf")
    void ownerCannotRevokeOwnOwnership() throws Exception {
        mockMvc.perform(post("/manage/t/corp/users/" + owner.id() + "/revoke-owner").session(ownerSession).with(csrf()))
            .andExpect(flash().attribute("errorMessage", "You cannot revoke your own ownership"));

        assertThat(userRepository.findById(owner.id()).orElseThrow().tenantOwner()).isTrue();
        assertNoAuditRow(tenant.id(), "tenant_ownership_revoked");
    }

    @Test
    @DisplayName("Service-level: revoking the last enabled tenant-owner is rejected — disabled co-owners do NOT count toward the quorum")
    void serviceLevelRevokeLastEnabledOwnerRejected() {
        // Boundary: a *disabled* tenant-owner exists alongside the (sole) enabled owner —
        // the disabled row does not count toward the enabled-owner quorum, so revoking
        // the only enabled owner is still rejected.
        userRepository.save(new User(
            null, tenant.id(), "disabled-owner@example.test", passwordEncoder.encode("pass"),
            /* enabled */ false, false, /* tenantOwner */ true, true, LocalDateTime.now()));
        User actor = userRepository.save(new User(
            null, tenant.id(), "actor@example.test", passwordEncoder.encode("pass"),
            true, false, false, true, LocalDateTime.now()));

        assertThatThrownBy(() -> userAdministration.revokeTenantOwnership(owner.id(), tenant.id(), actor.id()))
            .isInstanceOf(UserAdminException.WouldOrphanTenant.class)
            .hasMessageContaining("last enabled tenant owner");

        assertThat(userRepository.findById(owner.id()).orElseThrow().tenantOwner()).isTrue();
        assertNoAuditRow(tenant.id(), "tenant_ownership_revoked");
    }

    @Test
    @DisplayName("Service-level: revoking from a tenant with two enabled owners is allowed — count drops to one (still ≥ 1)")
    void serviceLevelRevokeWithTwoEnabledOwnersAllowed() {
        User co = seedUser("co@example.test", false, true);

        userAdministration.revokeTenantOwnership(co.id(), tenant.id(), owner.id());

        assertThat(userRepository.findById(co.id()).orElseThrow().tenantOwner()).isFalse();
    }

    @Test
    @DisplayName("Service-level: granting tenant-owner to a user already owning it is idempotent — no event emitted")
    void grantOwnerIsIdempotent() {
        User existingOwner = seedUser("co@example.test", false, true);

        userAdministration.grantTenantOwnership(existingOwner.id(), tenant.id(), owner.id());

        assertThat(countAuditRows(tenant.id(), "tenant_ownership_granted", existingOwner.id())).isZero();
    }

    @Test
    @DisplayName("Service-level: revoking tenant-owner from a non-owner user is idempotent — no event emitted")
    void revokeOwnerIsIdempotent() {
        User alice = seedUser("alice@example.test", false, false);

        userAdministration.revokeTenantOwnership(alice.id(), tenant.id(), owner.id());

        assertThat(countAuditRows(tenant.id(), "tenant_ownership_revoked", alice.id())).isZero();
    }

    // --- cross-tenant isolation ---

    @Test
    @DisplayName("Cross-tenant userId is rejected as UserNotInTenant — flash-redirects with 'User not found'")
    void crossTenantUserIdIsRejected() throws Exception {
        Tenant other = tenantRepository.save(new Tenant(null, "other", "Other", TenantStatus.ACTIVE, LocalDateTime.now()));
        User strangerInOther = userRepository.save(new User(
            null, other.id(), "stranger@example.test", passwordEncoder.encode("pass"),
            true, false, false, true, LocalDateTime.now()));

        mockMvc.perform(post("/manage/t/corp/users/" + strangerInOther.id() + "/disable").session(ownerSession).with(csrf()))
            .andExpect(redirectedUrl("/manage/t/corp/users"))
            .andExpect(flash().attribute("errorMessage", "User not found"));

        // The stranger row is untouched — cross-tenant isolation upheld.
        assertThat(userRepository.findById(strangerInOther.id()).orElseThrow().enabled()).isTrue();
    }

    // --- self-service change-password (preserved) ---

    @Test
    @DisplayName("A user with mustChangePassword=true is redirected to the change-password page on every authenticated request")
    void userWithMustChangePasswordIsInterceptedToChangePasswordPage() throws Exception {
        userRepository.save(new User(null, tenant.id(), "newuser@example.test",
            passwordEncoder.encode("temp"), true, true, false, true, LocalDateTime.now()));
        MockHttpSession session = loginAs("newuser@example.test", "temp");

        mockMvc.perform(get("/manage/t/corp/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/manage/t/corp/change-password"));
    }

    @Test
    @DisplayName("Forced change clears mustChangePassword AND writes a password_changed audit row with trigger=forced")
    void mustChangePasswordClearedAfterChange() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test",
            passwordEncoder.encode("temp"), true, true, false, true, LocalDateTime.now()));
        MockHttpSession session = loginAs("newuser@example.test", "temp");

        mockMvc.perform(post("/manage/t/corp/change-password").session(session).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isFalse();
        Map<String, Object> row = latestEventOfType(tenant.id(), "password_changed");
        assertThat(row.get("details").toString().replace(" ", "")).contains("\"trigger\":\"forced\"");
    }

    @Test
    @DisplayName("Self-service password change (no must-change flag) writes a password_changed audit row with trigger=self_service")
    void selfServiceChangePasswordEmitsSelfServiceTrigger() throws Exception {
        mockMvc.perform(post("/manage/t/corp/change-password").session(ownerSession).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "newpass123"))
            .andExpect(status().is3xxRedirection());

        Map<String, Object> row = latestEventOfType(tenant.id(), "password_changed");
        assertThat(row.get("details").toString().replace(" ", "")).contains("\"trigger\":\"self_service\"");
    }

    @Test
    @DisplayName("Mismatched new/confirm passwords re-render the form with an error and leave mustChangePassword=true")
    void changePasswordRedisplaysFormWhenNewAndConfirmDoNotMatch() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test",
            passwordEncoder.encode("temp"), true, true, false, true, LocalDateTime.now()));
        MockHttpSession session = loginAs("newuser@example.test", "temp");

        mockMvc.perform(post("/manage/t/corp/change-password").session(session).with(csrf())
                .param("newPassword", "newpass123").param("confirmPassword", "different1"))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("errorMessage", "Passwords do not match"));

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("Blank/whitespace new password re-renders the form with 'Password is required' and leaves mustChangePassword=true")
    void changePasswordRedisplaysFormWhenNewPasswordIsBlank() throws Exception {
        User newUser = userRepository.save(new User(null, tenant.id(), "newuser@example.test",
            passwordEncoder.encode("temp"), true, true, false, true, LocalDateTime.now()));
        MockHttpSession session = loginAs("newuser@example.test", "temp");

        mockMvc.perform(post("/manage/t/corp/change-password").session(session).with(csrf())
                .param("newPassword", "   ").param("confirmPassword", "   "))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("errorMessage", "Password is required"));

        assertThat(userRepository.findById(newUser.id()).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("GET /manage/t/{slug}/change-password renders the form template with the tenant slug in the model")
    void changePasswordFormGetRendersTemplateWithSlug() throws Exception {
        mockMvc.perform(get("/manage/t/corp/change-password").session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(view().name("manage/users/change-password"))
            .andExpect(model().attribute("slug", "corp"));
    }

    // --- helpers ---

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    private User seedUser(String email, boolean disabled, boolean tenantOwner) {
        return userRepository.save(new User(
            null, tenant.id(), email, passwordEncoder.encode("pass"),
            !disabled, false, tenantOwner, true, LocalDateTime.now()));
    }

    private MockHttpSession loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/manage/t/corp/login")
                .param("email", email).param("password", password).with(csrf()))
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private void assertAuditRow(Long tenantId, String eventType, Long actorUserId, Long targetUserId, String email) {
        Map<String, Object> row = latestEventOfType(tenantId, eventType);
        assertThat(row.get("actor_user_id")).isEqualTo(actorUserId);
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(targetUserId));
        assertThat(row.get("details").toString().replace(" ", "")).contains("\"email\":\"" + email + "\"");
    }

    private void assertAuditRowExists(Long tenantId, String eventType, Long targetUserId) {
        long count = countAuditRows(tenantId, eventType, targetUserId);
        assertThat(count).as("expected %s row for user %d", eventType, targetUserId).isGreaterThan(0);
    }

    private void assertNoAuditRow(Long tenantId, String eventType) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id FROM audit_event WHERE tenant_id = ? AND event_type = ?",
            tenantId, eventType);
        assertThat(rows).as("expected no %s rows", eventType).isEmpty();
    }

    private long countAuditRows(Long tenantId, String eventType, Long targetUserId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = ? AND target_id = ?",
            Long.class, tenantId, eventType, String.valueOf(targetUserId));
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestEventOfType(Long tenantId, String eventType) {
        return jdbcTemplate.queryForMap(
            "SELECT event_type, tenant_id, actor_user_id, target_type, target_id, details::text AS details "
                + "FROM audit_event WHERE tenant_id = ? AND event_type = ? ORDER BY occurred_at DESC LIMIT 1",
            tenantId, eventType);
    }
}

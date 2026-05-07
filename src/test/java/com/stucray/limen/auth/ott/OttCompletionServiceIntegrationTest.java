package com.stucray.limen.auth.ott;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boundary tests for {@link OttCompletionService}. The two completion verbs
 * are intent-specific by design — issue is uniform but completion is not — so
 * each gets its own scenario rather than being parameterised.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("OttCompletionService: VERIFY_EMAIL flips the bit (idempotent); PASSWORD_RESET emits the journey-tail marker without DB mutation")
class OttCompletionServiceIntegrationTest {

    @Autowired OttCompletionService completionService;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAudit() {
        jdbcTemplate.execute("DELETE FROM audit_event WHERE event_type IN "
            + "('email_verified', 'password_reset_completed')");
    }

    @Test
    @DisplayName("markEmailVerified flips users.email_verified=true and emits EmailVerifiedEvent")
    void markEmailVerifiedFlipsBitAndEmitsEvent() {
        String suffix = uniqueSuffix();
        String slug = "comp-" + suffix;
        String email = "owner-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Comp " + suffix);
        User user = userRepository.save(unverifiedUser(tenant.id(), email));

        completionService.markEmailVerified(user.id(), tenant.id());

        Boolean verified = jdbcTemplate.queryForObject(
            "SELECT email_verified FROM users WHERE id = ?",
            Boolean.class, user.id());
        assertThat(verified).isTrue();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(emailVerifiedCount(tenant.id(), user.id())).isEqualTo(1));
    }

    @Test
    @DisplayName("markEmailVerified is idempotent: a second call when already verified does not re-emit")
    void markEmailVerifiedIdempotent() {
        String suffix = uniqueSuffix();
        String slug = "comp2-" + suffix;
        String email = "owner-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Comp2 " + suffix);
        User user = userRepository.save(unverifiedUser(tenant.id(), email));

        completionService.markEmailVerified(user.id(), tenant.id());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(emailVerifiedCount(tenant.id(), user.id())).isEqualTo(1));

        completionService.markEmailVerified(user.id(), tenant.id());
        // Wait long enough that a duplicate row would have landed if it were
        // going to. The count must stay at 1.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(emailVerifiedCount(tenant.id(), user.id())).isEqualTo(1));
    }

    @Test
    @DisplayName("markPasswordResetCompleted emits PasswordResetCompletedEvent without mutating users")
    void markPasswordResetCompletedEmitsWithoutMutation() {
        String suffix = uniqueSuffix();
        String slug = "comp3-" + suffix;
        String email = "owner-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Comp3 " + suffix);
        User user = userRepository.save(verifiedUser(tenant.id(), email));
        String hashBefore = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, user.id());

        completionService.markPasswordResetCompleted(user.id(), tenant.id());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event "
                    + "WHERE event_type = 'password_reset_completed' "
                    + "AND tenant_id = ? AND target_id = ?",
                Integer.class, tenant.id(), String.valueOf(user.id()));
            assertThat(rows).isEqualTo(1);
        });

        String hashAfter = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, user.id());
        assertThat(hashAfter).isEqualTo(hashBefore);
    }

    private Integer emailVerifiedCount(Long tenantId, Long userId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_event "
                + "WHERE event_type = 'email_verified' "
                + "AND tenant_id = ? AND target_id = ?",
            Integer.class, tenantId, String.valueOf(userId));
    }

    private User unverifiedUser(Long tenantId, String email) {
        return new User(
            null, tenantId, email,
            passwordEncoder.encode("old-secret"),
            true, false, true, /* emailVerified */ false, LocalDateTime.now());
    }

    private User verifiedUser(Long tenantId, String email) {
        return new User(
            null, tenantId, email,
            passwordEncoder.encode("old-secret"),
            true, false, true, /* emailVerified */ true, LocalDateTime.now());
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

package com.stucray.limen.auth.ott;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boundary tests for {@link OttDispatcher}: parameterised over every
 * {@link OttIntent} so the dispatcher invariants (token row insert,
 * existence-oracle defence, audit emit, no-TenantScope-required) are
 * automatically asserted for every intent — adding a new intent picks up
 * the same coverage with zero test edits beyond a new handler bean.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("OttDispatcher.issue: token row + audit row + existence-oracle defence hold for every intent")
class OttDispatcherIntegrationTest {

    @Autowired OttDispatcher dispatcher;
    @Autowired TenantProvisioningService tenantProvisioningService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAudit() {
        jdbcTemplate.execute("DELETE FROM audit_event WHERE event_type IN "
            + "('verification_ott_issued', 'password_reset_ott_issued')");
    }

    /**
     * OttDispatcher resolves the magic-link base URL from the inbound servlet
     * request via {@link MagicLinkBuilder}. These tests drive the dispatcher
     * directly (no real HTTP), so we bind a mock request to the thread before
     * each test and clear it afterwards.
     */
    @BeforeEach
    void bindRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8090);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @EnumSource(OttIntent.class)
    @DisplayName("issue(intent, tenant, user) inserts the row and emits a delivered=true issued event")
    void knownUserIssueDelivers(OttIntent intent) {
        String suffix = uniqueSuffix();
        String slug = "disp-known-" + suffix;
        String email = "owner-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Disp " + suffix);
        User user = userRepository.save(activeUser(tenant.id(), email));

        // Caller has no TenantScope bound — dispatcher binds its own. This
        // mirrors the signup path: SignupService runs ahead of any tenant
        // routing filter, so the dispatcher cannot rely on inherited scope.
        assertThat(TenantScope.tenantId()).isNull();
        dispatcher.issue(intent, tenant, user);

        Integer tokenRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_tokens "
                + "WHERE tenant_id = ? AND username = ? AND intent = ?",
            Integer.class, tenant.id(), email, intent.wire());
        assertThat(tokenRows).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(latestIssuedRow(intent, tenant.id())).satisfies(row -> {
                assertThat(row.get("actor_user_id")).isEqualTo(user.id());
                assertThat(row.get("target_id")).isEqualTo(String.valueOf(user.id()));
                String details = String.valueOf(row.get("details")).replace(" ", "");
                assertThat(details).contains("\"delivered\":true");
                assertThat(details).contains(email);
            }));
    }

    @ParameterizedTest
    @EnumSource(OttIntent.class)
    @DisplayName("issue(intent, tenant, email) for a known email inserts a row and emits delivered=true with the user id")
    void knownEmailIssueDelivers(OttIntent intent) {
        String suffix = uniqueSuffix();
        String slug = "disp-email-" + suffix;
        String email = "owner-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Disp " + suffix);
        User user = userRepository.save(activeUser(tenant.id(), email));

        dispatcher.issue(intent, tenant, email);

        Integer tokenRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_tokens "
                + "WHERE tenant_id = ? AND username = ? AND intent = ?",
            Integer.class, tenant.id(), email, intent.wire());
        assertThat(tokenRows).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(latestIssuedRow(intent, tenant.id())).satisfies(row -> {
                assertThat(row.get("actor_user_id")).isEqualTo(user.id());
                String details = String.valueOf(row.get("details")).replace(" ", "");
                assertThat(details).contains("\"delivered\":true");
            }));
    }

    @ParameterizedTest
    @EnumSource(OttIntent.class)
    @DisplayName("issue(intent, tenant, email) for an unknown email is a silent no-op for delivery and emits delivered=false with null user (existence-oracle defence)")
    void unknownEmailIssueDoesNotDeliver(OttIntent intent) {
        String suffix = uniqueSuffix();
        String slug = "disp-unkn-" + suffix;
        String unknown = "ghost-" + suffix + "@example.test";
        Tenant tenant = tenantProvisioningService.createTenant(slug, "Disp " + suffix);

        dispatcher.issue(intent, tenant, unknown);

        Integer tokenRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_tokens "
                + "WHERE tenant_id = ? AND username = ?",
            Integer.class, tenant.id(), unknown);
        assertThat(tokenRows).isZero();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(latestIssuedRow(intent, tenant.id())).satisfies(row -> {
                assertThat(row.get("actor_user_id")).isNull();
                assertThat(row.get("target_id")).isNull();
                String details = String.valueOf(row.get("details")).replace(" ", "");
                assertThat(details).contains("\"delivered\":false");
                assertThat(details).contains(unknown);
            }));
    }

    private Map<String, Object> latestIssuedRow(OttIntent intent, Long tenantId) {
        String eventType = switch (intent) {
            case VERIFY_EMAIL -> "verification_ott_issued";
            case PASSWORD_RESET -> "password_reset_ott_issued";
        };
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT actor_user_id, target_id, details::text AS details FROM audit_event "
                + "WHERE event_type = ? AND tenant_id = ? "
                + "ORDER BY occurred_at DESC LIMIT 1",
            eventType, tenantId);
        assertThat(rows).isNotEmpty();
        return rows.get(0);
    }

    private User activeUser(Long tenantId, String email) {
        return new User(
            null, tenantId, email,
            passwordEncoder.encode("old-secret"),
            true, false, true, false, LocalDateTime.now());
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

package com.stucray.limen.audit.dispatch;

import com.stucray.limen.audit.events.AccountUnlockedEvent;
import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.audit.events.RateLimitHitEvent;
import com.stucray.limen.audit.events.TenantCreatedEvent;
import com.stucray.limen.audit.events.TenantDeletedEvent;
import com.stucray.limen.audit.events.VerificationResentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests on the registry — no Spring context. Each rule's projection
 * is invoked directly against a constructed event record and the resulting
 * row shape is asserted. Lookup behaviour (exact-class match, subclass
 * fallback, binding filter, no-match) is also covered here so issues surface
 * without booting the full app.
 */
@DisplayName("AuditRegistry rule lookup and projection shapes")
class AuditRegistryTest {

    private final AuditRegistry registry = new AuditRegistry();

    @Test
    @DisplayName("findRule resolves an exact class match")
    void findRuleExactMatch() {
        AuditRule<?> rule = registry.findRule(TenantCreatedEvent.class, AuditBinding.AFTER_COMMIT);
        assertThat(rule).isNotNull();
        assertThat(rule.eventType()).isEqualTo("tenant_created");
    }

    @Test
    @DisplayName("findRule falls back to assignability for Spring auth failure subtypes")
    void findRuleAssignableFallback() {
        AuditRule<?> rule = registry.findRule(
            AuthenticationFailureBadCredentialsEvent.class, AuditBinding.IMMEDIATE);
        assertThat(rule).isNotNull();
        assertThat(rule.eventType()).isEqualTo("login_failure");
        assertThat(rule.eventClass()).isEqualTo(AbstractAuthenticationFailureEvent.class);
    }

    @Test
    @DisplayName("findRule respects binding — TenantCreatedEvent has no IMMEDIATE rule")
    void findRuleBindingFilter() {
        assertThat(registry.findRule(TenantCreatedEvent.class, AuditBinding.IMMEDIATE)).isNull();
        assertThat(registry.findRule(RateLimitHitEvent.class, AuditBinding.AFTER_COMMIT)).isNull();
    }

    @Test
    @DisplayName("findRule returns null for unregistered classes")
    void findRuleNoMatch() {
        assertThat(registry.findRule(String.class, AuditBinding.IMMEDIATE)).isNull();
        assertThat(registry.findRule(String.class, AuditBinding.AFTER_COMMIT)).isNull();
    }

    @Test
    @DisplayName("Every rule's eventClass is registered exactly once across all bindings")
    void noDuplicateEventClasses() {
        // The constructor throws on duplicates; constructing succeeded, so by
        // contract all are unique. This test pins that invariant.
        long uniqueClasses = registry.rules().stream()
            .map(AuditRule::eventClass)
            .distinct()
            .count();
        assertThat(uniqueClasses).isEqualTo(registry.rules().size());
    }

    // --- Projection shape assertions: spot-check non-trivial rules. ---

    @Test
    @DisplayName("TenantCreatedEvent projection: tenant_id, actor, target=tenant, details")
    void tenantCreatedProjection() {
        TenantCreatedEvent event = new TenantCreatedEvent(42L, "acme", "Acme Inc", 7L);
        AuditRule.Projection p = projectionFor(TenantCreatedEvent.class, AuditBinding.AFTER_COMMIT, event);
        assertThat(p.tenantId()).isEqualTo(42L);
        assertThat(p.actorUserId()).isEqualTo(7L);
        assertThat(p.targetType()).isEqualTo("tenant");
        assertThat(p.targetId()).isEqualTo("42");
        assertThat(p.ipOverride()).isNull();
        assertThat(p.details())
            .containsEntry("slug", "acme")
            .containsEntry("displayName", "Acme Inc");
    }

    @Test
    @DisplayName("TenantDeletedEvent projection: tenant_id NULL, originalTenantId stashed in details")
    void tenantDeletedProjection() {
        TenantDeletedEvent event = new TenantDeletedEvent(99L, "gone", 7L);
        AuditRule.Projection p = projectionFor(TenantDeletedEvent.class, AuditBinding.AFTER_COMMIT, event);
        assertThat(p.tenantId()).isNull();
        assertThat(p.actorUserId()).isEqualTo(7L);
        assertThat(p.targetId()).isEqualTo("99");
        assertThat(p.details())
            .containsEntry("slug", "gone")
            .containsEntry("originalTenantId", 99L);
    }

    @Test
    @DisplayName("AccountUnlockedEvent projection: actor (admin) and target user diverge")
    void accountUnlockedProjection() {
        AccountUnlockedEvent event = new AccountUnlockedEvent(10L, /* admin */ 1L, /* locked user */ 5L, "u@x");
        AuditRule.Projection p = projectionFor(AccountUnlockedEvent.class, AuditBinding.AFTER_COMMIT, event);
        assertThat(p.actorUserId()).isEqualTo(1L);
        assertThat(p.targetType()).isEqualTo("user");
        assertThat(p.targetId()).isEqualTo("5");
    }

    @Test
    @DisplayName("VerificationResentEvent with null userId collapses target to null")
    void verificationResentNullUser() {
        VerificationResentEvent event = new VerificationResentEvent(10L, null, "x@x", false);
        AuditRule.Projection p = projectionFor(VerificationResentEvent.class, AuditBinding.AFTER_COMMIT, event);
        assertThat(p.targetType()).isNull();
        assertThat(p.targetId()).isNull();
        assertThat(p.actorUserId()).isNull();
        assertThat(p.details()).containsEntry("delivered", false);
    }

    @Test
    @DisplayName("PasswordChangedEvent projection records lowercased trigger")
    void passwordChangedProjection() {
        PasswordChangedEvent event = new PasswordChangedEvent(10L, 5L, PasswordChangedEvent.Trigger.ADMIN_RESET);
        AuditRule.Projection p = projectionFor(PasswordChangedEvent.class, AuditBinding.AFTER_COMMIT, event);
        assertThat(p.details()).containsEntry("trigger", "admin_reset");
    }

    @Test
    @DisplayName("RateLimitHitEvent projection passes its own ip via withIp(...)")
    void rateLimitHitProjection() {
        RateLimitHitEvent event = new RateLimitHitEvent("rule-1", "1.2.3.4", "/x", "GET", "1.2.3.4", 30);
        AuditRule.Projection p = projectionFor(RateLimitHitEvent.class, AuditBinding.IMMEDIATE, event);
        assertThat(p.tenantId()).isNull();
        assertThat(p.ipOverride()).isEqualTo("1.2.3.4");
        assertThat(p.details())
            .containsEntry("ruleId", "rule-1")
            .containsEntry("path", "/x")
            .containsEntry("retryAfterSeconds", 30L);
    }

    @Test
    @DisplayName("login_success rule short-circuits to null when principal isn't a tenant user")
    void loginSuccessShortCircuit() {
        Authentication anonymousAuth = new UsernamePasswordAuthenticationToken("not-a-tenant-user", "pw");
        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(anonymousAuth);
        AuditRule.Projection p = projectionFor(AuthenticationSuccessEvent.class, AuditBinding.IMMEDIATE, event);
        assertThat(p).isNull();
    }

    @Test
    @DisplayName("login_failure rule captures attemptedEmail when principal is a non-blank string")
    void loginFailureAttemptedEmail() {
        Authentication auth = new UsernamePasswordAuthenticationToken("attacker@x", "pw");
        AuthenticationFailureBadCredentialsEvent event = new AuthenticationFailureBadCredentialsEvent(
            auth, new BadCredentialsException("bad"));
        AuditRule.Projection p = projectionFor(
            AbstractAuthenticationFailureEvent.class, AuditBinding.IMMEDIATE, event);
        assertThat(p.details())
            .containsEntry("attemptedEmail", "attacker@x")
            .containsEntry("reason", "BadCredentialsException");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AuditRule.@org.jspecify.annotations.Nullable Projection projectionFor(
            Class<?> ruleClass, AuditBinding binding, Object event) {
        AuditRule rule = registry.findRule(ruleClass, binding);
        assertThat(rule).isNotNull();
        return (AuditRule.Projection) rule.project().apply(event);
    }
}

package com.stucray.limen.audit.dispatch;

import com.stucray.limen.audit.events.AccountLockedEvent;
import com.stucray.limen.audit.events.AccountUnlockedEvent;
import com.stucray.limen.audit.events.ClientSecretRotatedEvent;
import com.stucray.limen.audit.events.EmailVerifiedEvent;
import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.audit.events.PasswordResetCompletedEvent;
import com.stucray.limen.audit.events.PasswordResetOttIssuedEvent;
import com.stucray.limen.audit.events.RateLimitHitEvent;
import com.stucray.limen.audit.events.TenantCreatedEvent;
import com.stucray.limen.audit.events.TenantDeletedEvent;
import com.stucray.limen.audit.events.TenantOwnershipGrantedEvent;
import com.stucray.limen.audit.events.TenantOwnershipRevokedEvent;
import com.stucray.limen.audit.events.TenantSuspendedEvent;
import com.stucray.limen.audit.events.TenantUnsuspendedEvent;
import com.stucray.limen.audit.events.UserCreatedEvent;
import com.stucray.limen.audit.events.UserDeletedEvent;
import com.stucray.limen.audit.events.UserDisabledEvent;
import com.stucray.limen.audit.events.UserEnabledEvent;
import com.stucray.limen.audit.events.VerificationOttIssuedEvent;
import com.stucray.limen.audit.events.VerificationResentEvent;
import com.stucray.limen.auth.TenantUserDetails;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.stucray.limen.audit.dispatch.AuditBinding.AFTER_COMMIT;
import static com.stucray.limen.audit.dispatch.AuditBinding.IMMEDIATE;

/**
 * Single source of truth for what gets audited and how. Every audit-bearing
 * event-type maps to one entry in {@link #rules}; to add a new audit event,
 * define the event record under {@code audit/events/} and add one row here.
 *
 * <p>{@link AuditDispatcher} consumes this registry — it is the only caller.
 *
 * <p>Lookups go through {@link #findRule(Class, AuditBinding)}, which honours
 * Spring's listener-subtype contract: a rule registered for
 * {@link AbstractAuthenticationFailureEvent} matches concrete subclasses
 * (e.g. {@code AuthenticationFailureBadCredentialsEvent}) via
 * {@link Class#isAssignableFrom(Class)}.
 */
@Component
public class AuditRegistry {

    private final List<AuditRule<?>> rules = List.of(
        new AuditRule<>(AuthenticationSuccessEvent.class, "login_success", IMMEDIATE,
            event -> {
                TenantUserDetails p = principalOrNull(event.getAuthentication());
                if (p == null) return null;
                return AuditRule.Projection.ofUser(p.tenantId(), p.userId(), p.userId(),
                    Map.of("email", p.getUsername()));
            }),

        // attemptedEmail conditional → can't use Map.of (no null-safe builder).
        new AuditRule<>(AbstractAuthenticationFailureEvent.class, "login_failure", IMMEDIATE,
            event -> {
                Map<String, Object> details = new HashMap<>();
                details.put("reason", event.getException().getClass().getSimpleName());
                Authentication auth = event.getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof String s && !s.isBlank()) {
                    details.put("attemptedEmail", s);
                }
                return AuditRule.Projection.of(null, null, null, null, details);
            }),

        new AuditRule<>(TenantCreatedEvent.class, "tenant_created", AFTER_COMMIT,
            event -> AuditRule.Projection.of(
                event.tenantId(), event.actorUserId(),
                "tenant", String.valueOf(event.tenantId()),
                Map.of("slug", event.slug(), "displayName", event.displayName()))),

        new AuditRule<>(TenantSuspendedEvent.class, "tenant_suspended", AFTER_COMMIT,
            event -> AuditRule.Projection.of(
                event.tenantId(), event.actorUserId(),
                "tenant", String.valueOf(event.tenantId()),
                Map.of("slug", event.slug()))),

        new AuditRule<>(TenantUnsuspendedEvent.class, "tenant_unsuspended", AFTER_COMMIT,
            event -> AuditRule.Projection.of(
                event.tenantId(), event.actorUserId(),
                "tenant", String.valueOf(event.tenantId()),
                Map.of("slug", event.slug()))),

        // tenant_id on the row is null (FK SET NULL on tenant delete); the
        // original id is stashed in details so the trail is preserved.
        new AuditRule<>(TenantDeletedEvent.class, "tenant_deleted", AFTER_COMMIT,
            event -> AuditRule.Projection.of(
                null, event.actorUserId(),
                "tenant", String.valueOf(event.tenantId()),
                Map.of("slug", event.slug(), "originalTenantId", event.tenantId()))),

        new AuditRule<>(ClientSecretRotatedEvent.class, "client_secret_rotated", AFTER_COMMIT,
            event -> AuditRule.Projection.of(
                event.tenantId(), event.actorUserId(),
                "registered_client", event.registeredClientId(),
                Map.of())),

        new AuditRule<>(VerificationOttIssuedEvent.class, "verification_ott_issued", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(EmailVerifiedEvent.class, "email_verified", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(VerificationResentEvent.class, "verification_resent", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("email", event.email(), "delivered", event.delivered()))),

        new AuditRule<>(AccountLockedEvent.class, "account_locked", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("email", event.email(), "lockedUntil", event.lockedUntil().toString()))),

        // Actor (admin) and target user diverge by definition — a user cannot
        // unlock their own account.
        new AuditRule<>(AccountUnlockedEvent.class, "account_unlocked", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(PasswordResetOttIssuedEvent.class, "password_reset_ott_issued", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("email", event.email(), "delivered", event.delivered()))),

        new AuditRule<>(PasswordResetCompletedEvent.class, "password_reset_completed", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(), Map.of())),

        new AuditRule<>(PasswordChangedEvent.class, "password_changed", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.userId(), event.userId(),
                Map.of("trigger", event.trigger().name().toLowerCase()))),

        new AuditRule<>(UserCreatedEvent.class, "user_created", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(UserEnabledEvent.class, "user_enabled", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(UserDisabledEvent.class, "user_disabled", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(UserDeletedEvent.class, "user_deleted", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(TenantOwnershipGrantedEvent.class, "tenant_ownership_granted", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        new AuditRule<>(TenantOwnershipRevokedEvent.class, "tenant_ownership_revoked", AFTER_COMMIT,
            event -> AuditRule.Projection.ofUser(
                event.tenantId(), event.actorUserId(), event.userId(),
                Map.of("email", event.email()))),

        // Pre-auth event: the request is rejected before any controller runs,
        // so there's no servlet request-context the dispatcher can read. The
        // event captures the IP itself and the rule passes it through via
        // withIp(...). Conditional `key` → can't use Map.of.
        new AuditRule<>(RateLimitHitEvent.class, "rate_limit_hit", IMMEDIATE,
            event -> {
                Map<String, Object> details = new HashMap<>();
                details.put("ruleId", event.ruleId());
                if (event.key() != null) details.put("key", event.key());
                details.put("path", event.path());
                details.put("method", event.method());
                details.put("retryAfterSeconds", event.retryAfterSeconds());
                return AuditRule.Projection.of(null, null, null, null, details).withIp(event.ip());
            })
    );

    private final Map<CacheKey, Optional<AuditRule<?>>> resolutionCache = new ConcurrentHashMap<>();

    public AuditRegistry() {
        // Same event class registered twice would silently double-write. Catch
        // it at startup, where it's a one-line fix in this file.
        Map<Class<?>, AuditRule<?>> seen = new HashMap<>();
        for (AuditRule<?> rule : rules) {
            AuditRule<?> previous = seen.put(rule.eventClass(), rule);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate AuditRule for event class " + rule.eventClass().getName());
            }
        }
    }

    /**
     * Resolves the rule (if any) that should fire for {@code eventClass} in
     * {@code binding}. Honours Spring's listener-subtype contract: a rule whose
     * registered event class is a superclass of {@code eventClass} matches.
     * Results are cached per (class, binding) pair.
     */
    public @Nullable AuditRule<?> findRule(Class<?> eventClass, AuditBinding binding) {
        return resolutionCache
            .computeIfAbsent(new CacheKey(eventClass, binding), this::resolve)
            .orElse(null);
    }

    private Optional<AuditRule<?>> resolve(CacheKey key) {
        AuditRule<?> exact = null;
        AuditRule<?> assignable = null;
        for (AuditRule<?> rule : rules) {
            if (rule.binding() != key.binding()) continue;
            if (rule.eventClass().equals(key.eventClass())) {
                exact = rule;
                break;
            }
            if (rule.eventClass().isAssignableFrom(key.eventClass())) {
                assignable = rule;
            }
        }
        return Optional.ofNullable(exact != null ? exact : assignable);
    }

    public List<AuditRule<?>> rules() {
        return rules;
    }

    private static @Nullable TenantUserDetails principalOrNull(@Nullable Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof TenantUserDetails tud ? tud : null;
    }

    private record CacheKey(Class<?> eventClass, AuditBinding binding) {}
}

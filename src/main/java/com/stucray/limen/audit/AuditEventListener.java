package com.stucray.limen.audit;

import com.stucray.limen.audit.events.ClientSecretRotatedEvent;
import com.stucray.limen.audit.events.EmailVerifiedEvent;
import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.audit.events.TenantCreatedEvent;
import com.stucray.limen.audit.events.TenantDeletedEvent;
import com.stucray.limen.audit.events.TenantSuspendedEvent;
import com.stucray.limen.audit.events.TenantUnsuspendedEvent;
import com.stucray.limen.audit.events.VerificationOttIssuedEvent;
import com.stucray.limen.audit.events.VerificationResentEvent;
import com.stucray.limen.auth.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Single subscriber for every audit-bearing event. Spring Security's auth
 * events fire outside a database transaction and use plain {@link EventListener};
 * custom events emitted from within a service's {@code @Transactional} method
 * use {@link TransactionalEventListener} with {@link TransactionPhase#AFTER_COMMIT}
 * so a failed audit write cannot roll back the user-facing action.
 *
 * <p>Best-effort delivery for now: if the JVM crashes between transaction
 * commit and listener execution the event is lost. At-least-once arrives with
 * Spring Modulith adoption (architecture doc §6 v3.5) — at that point the
 * annotation swaps to {@code @ApplicationModuleListener} and the publication
 * registry persists pending events across restarts.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditEventWriter writer;

    public AuditEventListener(AuditEventWriter writer) {
        this.writer = writer;
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        TenantUserDetails principal = principalOrNull(auth);
        if (principal == null) {
            return;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("email", principal.getUsername());
        safeWrite(new AuditEvent(
            null, principal.tenantId(), principal.userId(),
            "login_success",
            "user", String.valueOf(principal.userId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        Map<String, Object> details = new HashMap<>();
        details.put("reason", event.getException().getClass().getSimpleName());
        if (auth != null) {
            Object principalValue = auth.getPrincipal();
            if (principalValue instanceof String s && !s.isBlank()) {
                details.put("attemptedEmail", s);
            }
        }
        safeWrite(new AuditEvent(
            null, null, null,
            "login_failure",
            null, null,
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantCreated(TenantCreatedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("slug", event.slug());
        details.put("displayName", event.displayName());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.actorUserId(),
            "tenant_created",
            "tenant", String.valueOf(event.tenantId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantSuspended(TenantSuspendedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("slug", event.slug());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.actorUserId(),
            "tenant_suspended",
            "tenant", String.valueOf(event.tenantId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantUnsuspended(TenantUnsuspendedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("slug", event.slug());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.actorUserId(),
            "tenant_unsuspended",
            "tenant", String.valueOf(event.tenantId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTenantDeleted(TenantDeletedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("slug", event.slug());
        details.put("originalTenantId", event.tenantId());
        safeWrite(new AuditEvent(
            null, null, event.actorUserId(),
            "tenant_deleted",
            "tenant", String.valueOf(event.tenantId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClientSecretRotated(ClientSecretRotatedEvent event) {
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.actorUserId(),
            "client_secret_rotated",
            "registered_client", event.registeredClientId(),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            Map.of()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationOttIssued(VerificationOttIssuedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("email", event.email());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.userId(),
            "verification_ott_issued",
            "user", String.valueOf(event.userId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerified(EmailVerifiedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("email", event.email());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.userId(),
            "email_verified",
            "user", String.valueOf(event.userId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationResent(VerificationResentEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("email", event.email());
        details.put("delivered", event.delivered());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.userId(),
            "verification_resent",
            event.userId() == null ? null : "user",
            event.userId() == null ? null : String.valueOf(event.userId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("trigger", event.trigger().name().toLowerCase());
        safeWrite(new AuditEvent(
            null, event.tenantId(), event.userId(),
            "password_changed",
            "user", String.valueOf(event.userId()),
            currentIp(), currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details));
    }

    private void safeWrite(AuditEvent event) {
        try {
            writer.write(event);
        } catch (RuntimeException e) {
            // AFTER_COMMIT means the parent transaction has already committed —
            // we cannot un-do it. Log and move on so the user-facing action stays
            // successful even if audit storage is degraded. (At-least-once is the
            // Modulith story; see class javadoc.)
            log.error("audit_event_write_failed event_type={} tenant_id={}",
                event.eventType(), event.tenantId(), e);
        }
    }

    private static @Nullable TenantUserDetails principalOrNull(@Nullable Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof TenantUserDetails tud ? tud : null;
    }

    private static @Nullable String currentIp() {
        HttpServletRequest req = currentRequestOrNull();
        return req == null ? null : req.getRemoteAddr();
    }

    private static @Nullable String currentUserAgent() {
        HttpServletRequest req = currentRequestOrNull();
        return req == null ? null : req.getHeader("User-Agent");
    }

    private static @Nullable HttpServletRequest currentRequestOrNull() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}

package com.stucray.limen.audit.dispatch;

import com.stucray.limen.audit.AuditEvent;
import com.stucray.limen.audit.AuditEventWriter;
import com.stucray.limen.audit.events.AuditedDomainEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Single subscriber for every audit-bearing event. Looks up the matching
 * {@link AuditRule} in {@link AuditRegistry}, applies its projection, fills
 * in ambient context (id, timestamp, ip / user-agent), and persists the row
 * via {@link AuditEventWriter}.
 *
 * <p>Two listener methods, one per binding:
 * <ul>
 *   <li>{@link AuditBinding#AFTER_COMMIT} via
 *       {@link ApplicationModuleListener @ApplicationModuleListener} for events
 *       emitted from inside a {@code @Transactional} method. The Modulith JDBC
 *       publication registry persists a row at publish time and clears it once
 *       the listener completes, giving at-least-once delivery: a JVM crash
 *       between commit and listener execution is replayed on the next startup.
 *       Duplicates are therefore possible on rare restart-after-failure (no
 *       idempotency key in this slice).</li>
 *   <li>{@link AuditBinding#IMMEDIATE} for pre-transaction events
 *       (rate-limit hits, Spring Security auth events). These fire outside any
 *       transaction so the publication registry can't engage; delivery is
 *       best-effort.</li>
 * </ul>
 *
 * <p>Write failures are logged and swallowed; for AFTER_COMMIT events Modulith
 * still records the publication so a stuck listener can be inspected and
 * resubmitted via the registry.
 */
@Component
class AuditDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuditDispatcher.class);

    private final AuditEventWriter writer;
    private final AuditRegistry registry;

    public AuditDispatcher(AuditEventWriter writer, AuditRegistry registry) {
        this.writer = writer;
        this.registry = registry;
    }

    /**
     * The parameter type is the {@link AuditedDomainEvent} marker rather than
     * {@code Object}: Modulith's {@code PersistentApplicationEventMulticaster}
     * persists every published event whose runtime type is assignable to the
     * declared listener parameter. A bare {@code Object} would catch every
     * Spring framework startup event ({@code ContextRefreshedEvent}, etc.) and
     * try to JSON-serialize the source — which is the entire application
     * context — into {@code event_publication}. Narrowing here scopes
     * persistence to the events the registry actually has rules for.
     */
    @ApplicationModuleListener
    public void onAfterCommit(AuditedDomainEvent event) {
        dispatch(event, AuditBinding.AFTER_COMMIT);
    }

    @EventListener
    public void onImmediate(Object event) {
        dispatch(event, AuditBinding.IMMEDIATE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatch(Object event, AuditBinding binding) {
        AuditRule rule = registry.findRule(event.getClass(), binding);
        if (rule == null) return;
        AuditRule.Projection projection = (AuditRule.Projection) rule.project().apply(event);
        if (projection == null) return;
        String ip = projection.ipOverride() != null ? projection.ipOverride() : currentIp();
        Map<String, Object> details = projection.details() == null ? Map.of() : projection.details();
        AuditEvent row = new AuditEvent(
            null,
            projection.tenantId(),
            projection.actorUserId(),
            rule.eventType(),
            projection.targetType(),
            projection.targetId(),
            ip,
            currentUserAgent(),
            LocalDateTime.now(ZoneId.systemDefault()),
            details);
        safeWrite(row);
    }

    private void safeWrite(AuditEvent event) {
        try {
            writer.write(event);
        } catch (RuntimeException e) {
            log.error("audit_event_write_failed event_type={} tenant_id={}",
                event.eventType(), event.tenantId(), e);
        }
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

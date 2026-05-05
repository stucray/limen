package com.stucray.limen.audit.dispatch;

import com.stucray.limen.audit.AuditEvent;
import com.stucray.limen.audit.AuditEventWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
 *   <li>{@link AuditBinding#AFTER_COMMIT} for events emitted from inside a
 *       {@code @Transactional} method — the row writes after commit so a
 *       write failure cannot roll back the user-facing action.</li>
 *   <li>{@link AuditBinding#IMMEDIATE} for pre-transaction events
 *       (rate-limit hits, Spring Security auth events).</li>
 * </ul>
 *
 * <p>Best-effort delivery — write failures are logged and swallowed.
 * At-least-once arrives with Spring Modulith adoption (architecture doc
 * §6 v3.5): the AFTER_COMMIT listener swaps to {@code @ApplicationModuleListener},
 * and event records gain ip / user-agent fields (since async listeners run
 * off-thread and {@link RequestContextHolder} is thread-local).
 */
@Component
public class AuditDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuditDispatcher.class);

    private final AuditEventWriter writer;
    private final AuditRegistry registry;

    public AuditDispatcher(AuditEventWriter writer, AuditRegistry registry) {
        this.writer = writer;
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAfterCommit(Object event) {
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

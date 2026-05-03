package com.stucray.limen.audit;

import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One row of the {@code audit_event} table. Constructed by listeners; written
 * by {@link AuditEventWriter}. Read paths (the audit UI in the v3.5 follow-up)
 * will hydrate this same shape from JDBC.
 */
public record AuditEvent(
    @Nullable Long id,
    @Nullable Long tenantId,
    @Nullable Long actorUserId,
    String eventType,
    @Nullable String targetType,
    @Nullable String targetId,
    @Nullable String ipAddress,
    @Nullable String userAgent,
    LocalDateTime occurredAt,
    Map<String, Object> details
) {}

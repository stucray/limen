package com.stucray.limen.audit.events;

/**
 * A Tenant row was hard-deleted. {@code tenantId} on the audit row is null
 * (the FK is SET NULL on tenant delete) — the listener stashes the original
 * id in the {@code details} payload so the trail is preserved.
 */
public record TenantDeletedEvent(
    Long tenantId,
    String slug,
    Long actorUserId
) {}

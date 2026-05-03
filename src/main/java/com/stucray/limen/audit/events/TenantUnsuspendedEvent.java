package com.stucray.limen.audit.events;

public record TenantUnsuspendedEvent(
    Long tenantId,
    String slug,
    Long actorUserId
) {}

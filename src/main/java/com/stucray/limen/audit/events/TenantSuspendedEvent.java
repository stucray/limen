package com.stucray.limen.audit.events;

public record TenantSuspendedEvent(
    Long tenantId,
    String slug,
    Long actorUserId
) implements AuditedDomainEvent {}

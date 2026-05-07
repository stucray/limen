package com.stucray.limen.audit.events;

public record SigningKeyPrunedEvent(
    Long tenantId,
    String kid
) implements AuditedDomainEvent {}

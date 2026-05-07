package com.stucray.limen.audit.events;

public record SigningKeyRotatedEvent(
    Long tenantId,
    String oldKid,
    String newKid
) implements AuditedDomainEvent {}

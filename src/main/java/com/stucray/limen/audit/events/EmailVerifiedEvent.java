package com.stucray.limen.audit.events;

/**
 * A user's email_verified flag transitioned from false to true after a
 * successful OTT consume with intent=verify-email.
 */
public record EmailVerifiedEvent(
    Long tenantId,
    Long userId,
    String email
) implements AuditedDomainEvent {}

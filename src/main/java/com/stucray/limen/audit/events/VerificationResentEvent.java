package com.stucray.limen.audit.events;

import org.jspecify.annotations.Nullable;

/**
 * A "resend verification email" request fired. {@code userId} is null when the
 * submitted email did not match any user in the tenant — we still emit the
 * event so audit records show the attempt, but nothing was sent.
 */
public record VerificationResentEvent(
    Long tenantId,
    @Nullable Long userId,
    String email,
    boolean delivered
) implements AuditedDomainEvent {}

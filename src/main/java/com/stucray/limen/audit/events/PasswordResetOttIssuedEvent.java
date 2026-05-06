package com.stucray.limen.audit.events;

import org.jspecify.annotations.Nullable;

/**
 * A "forgot password" request fired. {@code userId} is null when the submitted
 * email did not match any user in the tenant — we still emit the event so audit
 * records show the attempt, but nothing was sent. Mirrors
 * {@link VerificationResentEvent}: the controller-level response is identical
 * for known and unknown emails (no user-existence oracle), and the audit row's
 * {@code delivered} flag is the only place the distinction surfaces.
 */
public record PasswordResetOttIssuedEvent(
    Long tenantId,
    @Nullable Long userId,
    String email,
    boolean delivered
) implements AuditedDomainEvent {}

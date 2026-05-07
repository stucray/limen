package com.stucray.limen.audit.events;

import org.jspecify.annotations.Nullable;

/**
 * A verification OTT was issued for an email in a tenant. {@code userId} is
 * null when the submitted email did not match any user in the tenant — the
 * event is still emitted so audit rows show the attempt without leaking which
 * addresses are real (existence-oracle defence). {@code delivered} reflects
 * whether an email was sent: {@code true} for a known recipient, {@code false}
 * for the silent no-op path. Mirrors {@link PasswordResetOttIssuedEvent} so
 * both OTT-issue paths share one canonical shape.
 */
public record VerificationOttIssuedEvent(
    Long tenantId,
    @Nullable Long userId,
    String email,
    boolean delivered
) implements AuditedDomainEvent {}

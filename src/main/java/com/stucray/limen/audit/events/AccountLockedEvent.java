package com.stucray.limen.audit.events;

import java.time.LocalDateTime;

/**
 * A user account was just locked because consecutive failed-login attempts
 * crossed {@code limen.lockout.threshold}. {@code lockedUntil} is the wall-clock
 * timestamp the {@code TenantAuthProvider} pre-check compares against on the
 * next attempt; investigators reading audit need to know not just that a lock
 * happened but for how long.
 */
public record AccountLockedEvent(
    Long tenantId,
    Long userId,
    String email,
    LocalDateTime lockedUntil
) implements AuditedDomainEvent {}

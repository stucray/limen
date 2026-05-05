package com.stucray.limen.audit.events;

/**
 * A new user was created via the management UI. {@code actorUserId} is the
 * admin who performed the action; {@code userId} is the newly-created user.
 * Distinct from public self-signup, which goes through {@code TenantUserBootstrap}
 * and emits {@link TenantCreatedEvent} only.
 */
public record UserCreatedEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) {}

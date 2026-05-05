package com.stucray.limen.audit.events;

/**
 * An admin enabled a previously-disabled user account. {@code actorUserId} is
 * the admin; {@code userId} is the user whose account was enabled. The event
 * fires only when the flag actually transitions — re-enabling an already-enabled
 * user is a no-op and emits no event.
 */
public record UserEnabledEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) {}

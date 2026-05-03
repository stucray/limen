package com.stucray.limen.audit.events;

/**
 * A tenant admin cleared a user's lockout state via the management UI.
 * {@code actorUserId} is the admin who performed the action; {@code userId}
 * is the user whose account was unlocked. The two diverge by definition —
 * a user cannot unlock their own account.
 */
public record AccountUnlockedEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) {}

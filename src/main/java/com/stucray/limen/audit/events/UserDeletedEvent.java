package com.stucray.limen.audit.events;

/**
 * An admin hard-deleted a user account (the row is gone from the
 * {@code users} table). {@code actorUserId} is the admin; {@code userId}
 * is the original id of the deleted user — captured here because the row
 * no longer exists for an audit reader to dereference. Self-delete and
 * deleting the last enabled tenant-owner are both rejected.
 */
public record UserDeletedEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) implements AuditedDomainEvent {}

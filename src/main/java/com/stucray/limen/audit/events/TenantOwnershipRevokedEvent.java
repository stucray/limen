package com.stucray.limen.audit.events;

/**
 * An admin revoked the tenant-owner role from a user. {@code actorUserId}
 * is the revoking admin; {@code userId} is the now-former owner. Self-revoke
 * is rejected, as is revoking the last enabled tenant-owner — either would
 * orphan the tenant. Revoking from a user who is not an owner is a no-op
 * and emits no event.
 */
public record TenantOwnershipRevokedEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) implements AuditedDomainEvent {}

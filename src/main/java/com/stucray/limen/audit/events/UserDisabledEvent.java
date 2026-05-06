package com.stucray.limen.audit.events;

/**
 * An admin disabled a user account. {@code actorUserId} is the admin;
 * {@code userId} is the user whose account was disabled. The two diverge
 * by definition — disabling oneself is rejected as
 * {@code UserAdminException.CannotTargetSelf}. Disabling the last enabled
 * tenant-owner is rejected as {@code WouldOrphanTenant} so the tenant
 * keeps at least one administrator.
 */
public record UserDisabledEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) implements AuditedDomainEvent {}

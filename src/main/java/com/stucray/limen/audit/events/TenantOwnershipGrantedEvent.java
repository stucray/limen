package com.stucray.limen.audit.events;

/**
 * An admin granted the tenant-owner role to a user. {@code actorUserId} is
 * the granting admin; {@code userId} is the new tenant-owner. The target
 * must already be enabled and email-verified — granting ownership to a
 * disabled or unverified user is rejected as
 * {@code UserAdminException.TargetNotEligible}. Granting to a user who is
 * already an owner is a no-op and emits no event.
 */
public record TenantOwnershipGrantedEvent(
    Long tenantId,
    Long actorUserId,
    Long userId,
    String email
) {}

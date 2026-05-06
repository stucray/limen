package com.stucray.limen.audit.events;

import org.jspecify.annotations.Nullable;

/**
 * A new Tenant has been provisioned. Emitted by {@code TenantProvisioningService}
 * after both the tenants row and any seed signing key are inserted.
 *
 * <p>{@code actorUserId} is null when the tenant was created by an unauthenticated
 * caller (the public {@code /signup} path) and non-null when a system admin
 * created it via the upcoming tenant-create UI (slice #129).
 */
public record TenantCreatedEvent(
    Long tenantId,
    String slug,
    String displayName,
    @Nullable Long actorUserId
) implements AuditedDomainEvent {}

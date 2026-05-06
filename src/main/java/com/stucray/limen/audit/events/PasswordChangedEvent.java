package com.stucray.limen.audit.events;

/**
 * A user's password hash was successfully rotated. Distinguishes the trigger
 * via {@link Trigger}: forced (must_change_password flag), self-service
 * (user changed their own password), and admin-reset (a tenant admin reset
 * another user's password). The OTT-initiated path arrives in slice #126.
 */
public record PasswordChangedEvent(
    Long tenantId,
    Long userId,
    Trigger trigger
) implements AuditedDomainEvent {
    public enum Trigger { FORCED, SELF_SERVICE, ADMIN_RESET }
}

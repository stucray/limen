package com.stucray.limen.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
public record User(
    @Id Long id,
    Long tenantId,
    String email,
    String passwordHash,
    boolean enabled,
    boolean mustChangePassword,
    boolean tenantOwner,
    LocalDateTime createdAt
) {
    public User withPasswordHash(String newHash) {
        return new User(id, tenantId, email, newHash, enabled, true, tenantOwner, createdAt);
    }

    public User withEnabled(boolean newEnabled) {
        return new User(id, tenantId, email, passwordHash, newEnabled, mustChangePassword, tenantOwner, createdAt);
    }

    public User withMustChangePassword(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, value, tenantOwner, createdAt);
    }

    public User withTenantOwner(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, value, createdAt);
    }
}

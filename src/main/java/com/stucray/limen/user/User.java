package com.stucray.limen.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
public record User(
    @Id Long id,
    Long tenantId,
    String username,
    String passwordHash,
    boolean enabled,
    boolean mustChangePassword,
    boolean tenantOwner,
    LocalDateTime createdAt
) {
    public User withPasswordHash(String newHash) {
        return new User(id, tenantId, username, newHash, enabled, true, tenantOwner, createdAt);
    }

    public User withEnabled(boolean newEnabled) {
        return new User(id, tenantId, username, passwordHash, newEnabled, mustChangePassword, tenantOwner, createdAt);
    }

    public User withMustChangePassword(boolean value) {
        return new User(id, tenantId, username, passwordHash, enabled, value, tenantOwner, createdAt);
    }

    public User withTenantOwner(boolean value) {
        return new User(id, tenantId, username, passwordHash, enabled, mustChangePassword, value, createdAt);
    }
}

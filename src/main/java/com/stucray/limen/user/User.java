package com.stucray.limen.user;

import org.jspecify.annotations.Nullable;
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
    boolean emailVerified,
    int failedLoginAttempts,
    @Nullable LocalDateTime lockedUntil,
    LocalDateTime createdAt
) {
    /**
     * Pre-V9 9-arg constructor kept so the dozens of existing call sites
     * (production seeds + integration tests) don't need a sweep. New users
     * default to no failed attempts and no lockout, which matches the V9
     * column defaults — production paths that need to mutate lockout state
     * use {@link #withFailedLoginAttempts} / {@link #withLockedUntil}.
     */
    public User(
        Long id, Long tenantId, String email, String passwordHash,
        boolean enabled, boolean mustChangePassword, boolean tenantOwner,
        boolean emailVerified, LocalDateTime createdAt
    ) {
        this(id, tenantId, email, passwordHash, enabled, mustChangePassword,
            tenantOwner, emailVerified, 0, null, createdAt);
    }

    public User withPasswordHash(String newHash) {
        return new User(id, tenantId, email, newHash, enabled, true, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt);
    }

    public User withEnabled(boolean newEnabled) {
        return new User(id, tenantId, email, passwordHash, newEnabled, mustChangePassword, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt);
    }

    public User withMustChangePassword(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, value, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt);
    }

    public User withTenantOwner(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, value, emailVerified, failedLoginAttempts, lockedUntil, createdAt);
    }

    public User withEmailVerified(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, value, failedLoginAttempts, lockedUntil, createdAt);
    }

    public User withFailedLoginAttempts(int value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, emailVerified, value, lockedUntil, createdAt);
    }

    public User withLockedUntil(@Nullable LocalDateTime value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, emailVerified, failedLoginAttempts, value, createdAt);
    }
}

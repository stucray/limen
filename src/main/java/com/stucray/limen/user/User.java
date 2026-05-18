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
    LocalDateTime createdAt,
    @Nullable String fullName
) {
    /**
     * Pre-V9 9-arg constructor kept so the dozens of existing call sites
     * (production seeds + integration tests) don't need a sweep. New users
     * default to no failed attempts and no lockout, which matches the V9
     * column defaults, and a null full_name, which matches the V10 default.
     * Production paths that need to mutate lockout state use
     * {@link #withFailedLoginAttempts} / {@link #withLockedUntil}; paths that
     * have a name to set use {@link #withFullName} or the 10-arg constructor.
     */
    public User(
        Long id, Long tenantId, String email, String passwordHash,
        boolean enabled, boolean mustChangePassword, boolean tenantOwner,
        boolean emailVerified, LocalDateTime createdAt
    ) {
        this(id, tenantId, email, passwordHash, enabled, mustChangePassword,
            tenantOwner, emailVerified, 0, null, createdAt, null);
    }

    public User withPasswordHash(String newHash) {
        return new User(id, tenantId, email, newHash, enabled, true, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt, fullName);
    }

    public User withEnabled(boolean newEnabled) {
        return new User(id, tenantId, email, passwordHash, newEnabled, mustChangePassword, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt, fullName);
    }

    public User withMustChangePassword(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, value, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt, fullName);
    }

    public User withTenantOwner(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, value, emailVerified, failedLoginAttempts, lockedUntil, createdAt, fullName);
    }

    public User withEmailVerified(boolean value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, value, failedLoginAttempts, lockedUntil, createdAt, fullName);
    }

    public User withFailedLoginAttempts(int value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, emailVerified, value, lockedUntil, createdAt, fullName);
    }

    public User withLockedUntil(@Nullable LocalDateTime value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, emailVerified, failedLoginAttempts, value, createdAt, fullName);
    }

    public User withFullName(@Nullable String value) {
        return new User(id, tenantId, email, passwordHash, enabled, mustChangePassword, tenantOwner, emailVerified, failedLoginAttempts, lockedUntil, createdAt, value);
    }
}

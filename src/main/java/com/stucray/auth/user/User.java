package com.stucray.auth.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
public record User(
    @Id Long id,
    String username,
    String passwordHash,
    boolean enabled,
    LocalDateTime createdAt
) {
    public User withPasswordHash(String newHash) {
        return new User(id, username, newHash, enabled, createdAt);
    }
}

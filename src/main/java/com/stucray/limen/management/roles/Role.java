package com.stucray.limen.management.roles;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("role")
public record Role(
    @Id Long id,
    Long applicationId,
    String name,
    String description,
    LocalDateTime createdAt
) {
    public Role withName(String newName) {
        return new Role(id, applicationId, newName, description, createdAt);
    }

    public Role withDescription(String newDescription) {
        return new Role(id, applicationId, name, newDescription, createdAt);
    }
}

package com.stucray.limen.roles;

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
    Role withName(String newName) {
        return new Role(id, applicationId, newName, description, createdAt);
    }

    Role withDescription(String newDescription) {
        return new Role(id, applicationId, name, newDescription, createdAt);
    }
}

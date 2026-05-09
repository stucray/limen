package com.stucray.limen.applications;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("applications")
public record Application(
    @Id Long id,
    Long tenantId,
    String name,
    String description,
    LocalDateTime createdAt
) {
    Application withName(String newName) {
        return new Application(id, tenantId, newName, description, createdAt);
    }

    Application withDescription(String newDescription) {
        return new Application(id, tenantId, name, newDescription, createdAt);
    }
}

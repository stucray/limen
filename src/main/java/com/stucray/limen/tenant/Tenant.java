package com.stucray.limen.tenant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("tenants")
public record Tenant(
    @Id Long id,
    String slug,
    String displayName,
    TenantStatus status,
    LocalDateTime createdAt
) {
    public Tenant withDisplayName(String newDisplayName) {
        return new Tenant(id, slug, newDisplayName, status, createdAt);
    }

    public Tenant withStatus(TenantStatus newStatus) {
        return new Tenant(id, slug, displayName, newStatus, createdAt);
    }

    public boolean isSystem() {
        return "system".equals(slug);
    }
}

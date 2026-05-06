package com.stucray.limen.clients;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("client_metadata")
public record TenantClient(
    @Id Long id,
    String registeredClientId,
    Long applicationId,
    Long tenantId,
    String displayName,
    boolean confidential
) {}

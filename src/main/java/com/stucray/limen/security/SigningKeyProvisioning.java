package com.stucray.limen.security;

/**
 * Provisioning surface for per-tenant signing keys: seed a fresh ACTIVE key
 * on tenant on-boarding, drop every key on tenant off-boarding. Cross-module
 * public API consumed by {@code provisioning.TenantProvisioningService}.
 */
public interface SigningKeyProvisioning {

    void createForTenant(long tenantId);

    void deleteForTenant(long tenantId);
}

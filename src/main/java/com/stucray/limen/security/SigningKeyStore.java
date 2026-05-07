package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.jspecify.annotations.Nullable;

public interface SigningKeyStore {

    void createForTenant(long tenantId);

    @Nullable RSAKey getActiveSigningKey(long tenantId);

    /**
     * Returns the tenant's full key set ordered ACTIVE-first (then RETIRED in
     * created-at-newest order). Order matters: SAS's first-match selector picks
     * the head of the list at sign-time, so ACTIVE-first guarantees signing
     * keeps using the live key while the JWKS endpoint advertises both states
     * across a rotation grace window.
     */
    JWKSet getJwkSet(long tenantId);

    void deleteForTenant(long tenantId);

    /**
     * Rotates the tenant's ACTIVE signing key: marks the current ACTIVE row
     * RETIRED with {@code retired_at = now()}, then inserts a freshly-generated
     * ACTIVE row. Both writes happen in one transaction; order is forced by
     * the partial unique index {@code tenant_signing_key_one_active_per_tenant}.
     *
     * @throws IllegalStateException if the tenant has no ACTIVE key to rotate.
     */
    RotationOutcome rotateForTenant(long tenantId);

    record RotationOutcome(String oldKid, String newKid) {}
}

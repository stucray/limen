package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.jspecify.annotations.Nullable;

/**
 * Read surface for per-tenant signing keys: the ACTIVE key for signing and
 * the full key set for the JWKS endpoint. Cross-module public API consumed
 * by {@code oauth2.TenantJwkSource}.
 */
public interface SigningKeyReader {

    @Nullable RSAKey getActiveSigningKey(long tenantId);

    /**
     * Returns the tenant's full key set ordered ACTIVE-first (then RETIRED in
     * created-at-newest order). Order matters: SAS's first-match selector picks
     * the head of the list at sign-time, so ACTIVE-first guarantees signing
     * keeps using the live key while the JWKS endpoint advertises both states
     * across a rotation grace window.
     */
    JWKSet getJwkSet(long tenantId);
}

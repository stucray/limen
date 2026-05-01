package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.jspecify.annotations.Nullable;

public interface SigningKeyStore {

    void createForTenant(long tenantId);

    @Nullable RSAKey getActiveSigningKey(long tenantId);

    JWKSet getJwkSet(long tenantId);

    void deleteForTenant(long tenantId);
}

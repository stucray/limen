package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

public interface SigningKeyStore {

    void createForTenant(long tenantId);

    RSAKey getActiveSigningKey(long tenantId);

    JWKSet getJwkSet(long tenantId);

    void deleteForTenant(long tenantId);
}

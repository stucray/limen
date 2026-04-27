package com.stucray.limen.oauth2;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stucray.limen.security.SigningKeyStore;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;

import java.util.List;

public class TenantJwkSource implements JWKSource<SecurityContext> {

    private static final String TENANT_PATH_SEGMENT = "/t/";

    private final TenantRepository tenantRepository;
    private final SigningKeyStore signingKeyStore;

    public TenantJwkSource(TenantRepository tenantRepository, SigningKeyStore signingKeyStore) {
        this.tenantRepository = tenantRepository;
        this.signingKeyStore = signingKeyStore;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext securityContext) throws KeySourceException {
        Long tenantId = resolveTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "TenantJwkSource invoked with no tenant context — no AuthorizationServerContext issuer and no TenantScope"
            );
        }
        RSAKey activeKey = signingKeyStore.getActiveSigningKey(tenantId);
        if (activeKey == null) {
            throw new IllegalStateException("No active signing key for tenant " + tenantId);
        }
        return selector.select(new JWKSet(activeKey));
    }

    private Long resolveTenantId() {
        AuthorizationServerContext context = AuthorizationServerContextHolder.getContext();
        if (context != null) {
            String issuer = context.getIssuer();
            if (issuer != null) {
                int idx = issuer.lastIndexOf(TENANT_PATH_SEGMENT);
                if (idx >= 0) {
                    String slug = issuer.substring(idx + TENANT_PATH_SEGMENT.length());
                    Long fromIssuer = tenantRepository.findBySlug(slug).map(Tenant::id).orElse(null);
                    if (fromIssuer != null) return fromIssuer;
                }
            }
        }
        return TenantScope.tenantId();
    }
}

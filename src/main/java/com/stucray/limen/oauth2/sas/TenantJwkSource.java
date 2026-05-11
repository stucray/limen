package com.stucray.limen.oauth2.sas;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stucray.limen.security.SigningKeyReader;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;

import java.util.List;

/**
 * Branches by selector intent. SAS uses this {@link JWKSource} for two
 * distinct callers:
 *
 * <ul>
 *   <li><b>Signing</b> ({@code NimbusJwtEncoder.selectJwk}): the selector
 *       constrains keyType/keyUse/algorithm. The encoder throws if the source
 *       returns more than one match, so we return only the ACTIVE key (with
 *       private material decrypted by {@link SigningKeyReader#getActiveSigningKey}).</li>
 *   <li><b>JWKS endpoint</b> ({@code NimbusJwkSetEndpointFilter}): the selector
 *       has an empty matcher (match-all). We return every key for the tenant
 *       (ACTIVE + any RETIRED still inside the grace window), public-only via
 *       {@link SigningKeyReader#getJwkSet}, so resource servers can validate
 *       tokens signed by either key throughout a rotation overlap.</li>
 * </ul>
 *
 * <p>Distinguishing intent by inspecting the selector's matcher is brittle if
 * a third caller appears, but SAS only invokes this source via those two paths
 * today and the dispatch is asserted by unit tests.
 */
public class TenantJwkSource implements JWKSource<SecurityContext> {

    private static final String TENANT_PATH_SEGMENT = "/t/";

    private final TenantRepository tenantRepository;
    private final SigningKeyReader signingKeys;

    TenantJwkSource(TenantRepository tenantRepository, SigningKeyReader signingKeys) {
        this.tenantRepository = tenantRepository;
        this.signingKeys = signingKeys;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext securityContext) throws KeySourceException {
        Long tenantId = resolveTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "TenantJwkSource invoked with no tenant context — no AuthorizationServerContext issuer and no TenantScope"
            );
        }
        if (isMatchAllSelector(selector)) {
            JWKSet allPublic = signingKeys.getJwkSet(tenantId);
            if (allPublic.getKeys().isEmpty()) {
                throw new IllegalStateException("No signing keys for tenant " + tenantId);
            }
            return selector.select(allPublic);
        }
        RSAKey activeKey = signingKeys.getActiveSigningKey(tenantId);
        if (activeKey == null) {
            throw new IllegalStateException("No active signing key for tenant " + tenantId);
        }
        return selector.select(new JWKSet(activeKey));
    }

    private static boolean isMatchAllSelector(JWKSelector selector) {
        // SAS's JWKS-endpoint filter passes `new JWKSelector(new JWKMatcher.Builder().build())`,
        // i.e. a matcher with no constraints. The signing path constrains at least keyType,
        // keyUse, and algorithm.
        var m = selector.getMatcher();
        return m.getKeyTypes() == null
            && m.getKeyUses() == null
            && m.getAlgorithms() == null
            && m.getKeyIDs() == null;
    }

    private @Nullable Long resolveTenantId() {
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

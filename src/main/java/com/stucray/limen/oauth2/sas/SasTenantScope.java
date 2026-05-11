package com.stucray.limen.oauth2.sas;

import com.stucray.limen.tenant.TenantScope;

/**
 * Shared SAS-adapter helper: read {@link TenantScope#tenantId()} and throw
 * {@link IllegalStateException} if it is null, naming the calling adapter in
 * the message.
 *
 * <p>The asymmetry is the design: this helper is used by adapters whose SPI
 * contract has no legitimate cross-tenant read path (authorizations, consents).
 * Adapters with different missing-scope semantics keep their own resolution:
 *
 * <ul>
 *   <li>{@code TenantAwareRegisteredClientRepository} deliberately allows null
 *       scope so the management console can list clients across tenants — it
 *       branches inline rather than calling this helper.</li>
 *   <li>{@code TenantJwkSource} runs a two-mechanism resolution (issuer URL
 *       parse → scope fallback) that is specific to JWKS; it keeps its own
 *       private resolver.</li>
 * </ul>
 *
 * Which adapters call this helper is therefore the load-bearing contract about
 * "this SPI is hard-fail on missing scope" — don't widen casually.
 */
final class SasTenantScope {

    private SasTenantScope() {}

    static Long requireTenantId(String callerName) {
        Long tenantId = TenantScope.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException(callerName + " called without TenantScope");
        }
        return tenantId;
    }
}

package com.stucray.limen.oauth2.sas;

import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.tenant.TenantScope;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Wraps JdbcRegisteredClientRepository and rejects lookups for clients that do not
 * belong to the tenant currently bound on TenantScope.
 *
 * <p>Deliberately allows a null TenantScope so the management console (which
 * reads across tenants) can list clients without binding a scope. This is the
 * one adapter in {@code oauth2.sas} that does not delegate to
 * {@link SasTenantScope#requireTenantId} — see that helper's javadoc.
 */
class TenantAwareRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final TenantClientRepository tenantClientRepository;

    TenantAwareRegisteredClientRepository(
        RegisteredClientRepository delegate,
        TenantClientRepository tenantClientRepository
    ) {
        this.delegate = delegate;
        this.tenantClientRepository = tenantClientRepository;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
    }

    @Override
    public @Nullable RegisteredClient findById(String id) {
        RegisteredClient rc = delegate.findById(id);
        return checkTenantOwnership(rc);
    }

    @Override
    public @Nullable RegisteredClient findByClientId(String clientId) {
        RegisteredClient rc = delegate.findByClientId(clientId);
        return checkTenantOwnership(rc);
    }

    private @Nullable RegisteredClient checkTenantOwnership(@Nullable RegisteredClient rc) {
        if (rc == null) return null;
        Long currentTenantId = TenantScope.tenantId();
        if (currentTenantId == null) return rc; // non-tenant context (e.g. management console)

        boolean owned = tenantClientRepository
            .findByRegisteredClientIdAndTenantId(rc.getId(), currentTenantId)
            .isPresent();
        return owned ? rc : null;
    }
}

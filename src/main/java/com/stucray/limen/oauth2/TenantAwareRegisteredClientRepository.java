package com.stucray.limen.oauth2;

import com.stucray.limen.management.clients.TenantClientRepository;
import com.stucray.limen.tenant.TenantScope;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Wraps JdbcRegisteredClientRepository and rejects lookups for clients that do not
 * belong to the tenant currently bound on TenantScope.
 */
public class TenantAwareRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final TenantClientRepository tenantClientRepository;

    public TenantAwareRegisteredClientRepository(
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
    public RegisteredClient findById(String id) {
        RegisteredClient rc = delegate.findById(id);
        return checkTenantOwnership(rc);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        RegisteredClient rc = delegate.findByClientId(clientId);
        return checkTenantOwnership(rc);
    }

    private RegisteredClient checkTenantOwnership(RegisteredClient rc) {
        if (rc == null) return null;
        Long currentTenantId = TenantScope.tenantId();
        if (currentTenantId == null) return rc; // non-tenant context (e.g. management console)

        boolean owned = tenantClientRepository
            .findByRegisteredClientIdAndTenantId(rc.getId(), currentTenantId)
            .isPresent();
        return owned ? rc : null;
    }
}

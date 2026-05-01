package com.stucray.limen.tenant;

import com.stucray.limen.security.SigningKeyStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class TenantProvisioningService {

    private final TenantRepository tenantRepository;
    private final SigningKeyStore signingKeyStore;

    public TenantProvisioningService(
        TenantRepository tenantRepository,
        SigningKeyStore signingKeyStore
    ) {
        this.tenantRepository = tenantRepository;
        this.signingKeyStore = signingKeyStore;
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public Tenant createTenant(String slug, String displayName) {
        Tenant tenant = tenantRepository.save(
            new Tenant(null, slug, displayName, TenantStatus.ACTIVE, LocalDateTime.now())
        );
        if (!tenant.isSystem()) {
            signingKeyStore.createForTenant(tenant.id());
        }
        return tenant;
    }

    public void deleteTenant(long id) {
        tenantRepository.deleteById(id);
    }
}

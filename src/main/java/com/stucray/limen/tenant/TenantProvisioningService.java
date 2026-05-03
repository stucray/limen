package com.stucray.limen.tenant;

import com.stucray.limen.audit.events.TenantCreatedEvent;
import com.stucray.limen.audit.events.TenantDeletedEvent;
import com.stucray.limen.audit.events.TenantSuspendedEvent;
import com.stucray.limen.audit.events.TenantUnsuspendedEvent;
import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.security.SigningKeyStore;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class TenantProvisioningService {

    private final TenantRepository tenantRepository;
    private final SigningKeyStore signingKeyStore;
    private final ApplicationEventPublisher eventPublisher;

    public TenantProvisioningService(
        TenantRepository tenantRepository,
        SigningKeyStore signingKeyStore,
        ApplicationEventPublisher eventPublisher
    ) {
        this.tenantRepository = tenantRepository;
        this.signingKeyStore = signingKeyStore;
        this.eventPublisher = eventPublisher;
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public Tenant createTenant(String slug, String displayName) {
        Tenant tenant = tenantRepository.save(
            new Tenant(null, slug, displayName, TenantStatus.ACTIVE, LocalDateTime.now())
        );
        if (!tenant.isSystem()) {
            signingKeyStore.createForTenant(tenant.id());
        }
        eventPublisher.publishEvent(
            new TenantCreatedEvent(tenant.id(), tenant.slug(), tenant.displayName(), currentActorUserId()));
        return tenant;
    }

    public void suspend(Tenant tenant, long actorUserId) {
        tenantRepository.save(tenant.withStatus(TenantStatus.SUSPENDED));
        eventPublisher.publishEvent(
            new TenantSuspendedEvent(tenant.id(), tenant.slug(), actorUserId));
    }

    public void unsuspend(Tenant tenant, long actorUserId) {
        tenantRepository.save(tenant.withStatus(TenantStatus.ACTIVE));
        eventPublisher.publishEvent(
            new TenantUnsuspendedEvent(tenant.id(), tenant.slug(), actorUserId));
    }

    public void delete(Tenant tenant, long actorUserId) {
        tenantRepository.delete(tenant);
        eventPublisher.publishEvent(
            new TenantDeletedEvent(tenant.id(), tenant.slug(), actorUserId));
    }

    public void deleteTenant(long id) {
        tenantRepository.deleteById(id);
    }

    private static @Nullable Long currentActorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            return details.userId();
        }
        return null;
    }
}

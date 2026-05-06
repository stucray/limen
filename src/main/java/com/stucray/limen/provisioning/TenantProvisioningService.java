package com.stucray.limen.provisioning;

import com.stucray.limen.audit.events.TenantCreatedEvent;
import com.stucray.limen.audit.events.TenantDeletedEvent;
import com.stucray.limen.audit.events.TenantSuspendedEvent;
import com.stucray.limen.audit.events.TenantUnsuspendedEvent;
import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.security.SigningKeyStore;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Tenant lifecycle service: create / suspend / unsuspend / delete. The
 * {@code createTenant} entry point is intentionally narrow — it inserts the
 * tenant row, seeds a per-tenant signing key (skipped for the system tenant),
 * and emits {@code TenantCreatedEvent}. It does <em>not</em> bootstrap an
 * owner user or issue a verification OTT.
 *
 * <p>For the production "tenant + owner + verification" provisioning path
 * driven by {@code /signup} or {@code /manage/system/tenants/new}, go through
 * {@code tenant.provisioning.TenantProvisioner} — that's the deep module that
 * owns input normalisation, validation, and atomic orchestration. This class
 * is its private collaborator for the tenant-row + signing-key + audit-event
 * step.
 *
 * <p>{@code createTenant} stays {@code public} (rather than dropping to
 * package-private) for two callers that legitimately want a bare tenant
 * without an owner: the {@code identity.UserBootstrap} startup runner that
 * seeds the system tenant, and integration-test fixtures that need an
 * isolated tenant for some other feature's test scenario.
 */
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

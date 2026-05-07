package com.stucray.limen.applications;

import org.springframework.stereotype.Component;

/**
 * Tenant-scoped Application access. The tenant predicate is the security
 * boundary that any caller wanting an Application within a tenant must
 * apply — centralised here so it cannot be silently dropped.
 *
 * <p>Callers without a {@code tenantId} (sysadmin or system-tenant paths)
 * should not use this class; they should fetch the Application directly
 * with their own justification visible at the call site.
 */
@Component
public class ApplicationLookup {

    private final ApplicationRepository applicationRepository;

    public ApplicationLookup(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    /**
     * Returns the Application iff it exists and belongs to {@code tenantId}.
     *
     * @throws IllegalArgumentException {@code "Application not found"} when
     *         the Application is absent or owned by a different tenant.
     *         Cross-tenant access is rejected with the same message as
     *         "absent" so the response does not reveal the real owner.
     */
    public Application require(Long applicationId, Long tenantId) {
        return applicationRepository.findByIdAndTenantId(applicationId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Application not found"));
    }
}

/**
 * The {@code Tenant} entity, its repository, status enum, and the per-request
 * {@code TenantScope} {@code ScopedValue}.
 *
 * <p>{@code TenantScope} is the tenant-binding mechanism every layer reads to
 * answer "which Tenant is this request for?" — it is established by the
 * routing/issuer-context filters and consumed by the storage decorators, the
 * signing-key store, audit, observability, etc. Keeping it here (not in
 * {@code auth} or {@code oauth2}) means every other module can depend on
 * {@code tenant} without picking up authentication or protocol baggage.
 *
 * <p>Tenant *lifecycle* (create/suspend/unsuspend/delete) lives in the
 * {@code provisioning} module — separate to avoid a {@code tenant → auth → tenant}
 * cycle. This module is just the data shape and the request-scoped binding.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §3 (Domain Model) and §4.15 (Package structure).
 */
package com.stucray.limen.tenant;

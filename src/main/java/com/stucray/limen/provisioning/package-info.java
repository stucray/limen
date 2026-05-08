/**
 * Tenant lifecycle orchestration: create, suspend, unsuspend, delete.
 *
 * <p>{@code TenantProvisioningService} is the create/suspend/unsuspend/delete
 * surface called by both the public {@code /signup} flow and the System Admin
 * tenant-create UI; {@code TenantProvisioner} is the deep module that performs
 * the atomic create — Tenant row + initial signing key + owner User + verification
 * email — entered through both signup paths.
 *
 * <p>This module exists *separately* from {@code tenant} on purpose. Provisioning
 * depends on {@code tenant}, {@code user}, {@code auth}, {@code email}, and
 * {@code audit}; nesting it under {@code tenant} produces a real cycle
 * ({@code tenant → auth → tenant}). Promoting orchestration to its own module
 * surfaces its role and breaks the cycle.
 *
 * <p>Three "tenant lifecycle" entry points exist and are deliberately distinct:
 * <ul>
 *   <li>{@code provisioning} (this module) — the orchestrator both signup paths call into
 *   <li>{@code signup} — the public self-service form for new Tenants
 *   <li>{@code system} — the System Admin equivalent (create/suspend/unsuspend/delete from {@code /manage/system/...})
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure).
 */
package com.stucray.limen.provisioning;

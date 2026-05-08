/**
 * Cross-tenant System Admin controllers: the surfaces only a System Admin sees.
 *
 * <p>{@code SystemAdminController} hosts the {@code /manage/system/tenants}
 * landing page (list all Tenants) and the suspend/unsuspend/delete actions on
 * Tenants other than your own. {@code TenantDetailsController} hosts the
 * per-Tenant detail page plus the System-Admin tenant-create UI at
 * {@code /manage/system/tenants/new}, which delegates to {@code provisioning}.
 *
 * <p>The System Tenant itself ({@code slug = "system"}) is created by Flyway +
 * the {@code identity} bootstrap path, not through this module. This module is
 * about *what a System Admin can do across other Tenants*.
 *
 * <p>Three "tenant lifecycle" entry points exist and are deliberately distinct:
 * <ul>
 *   <li>{@code system} (this module) — System Admin actions on existing Tenants + create
 *   <li>{@code signup} — public self-service form for new Tenants
 *   <li>{@code provisioning} — the orchestrator both entry points call into
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure).
 */
package com.stucray.limen.system;

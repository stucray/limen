/**
 * Bootstrap-admin identity: properties + {@code UserBootstrap} startup runner.
 *
 * <p>On first boot the {@code UserBootstrap} runner ensures a System Admin User
 * exists in the System Tenant using credentials supplied by environment variables
 * ({@code LIMEN_BOOTSTRAP_ADMIN_*}). Once the System Tenant has a usable owner,
 * this module has no further role — it is intentionally a startup-only
 * concern.
 *
 * <p>Not to be confused with {@code com.stucray.limen.user} (the User entity itself)
 * or {@code com.stucray.limen.useradmin} (Tenant-Owner administration of Users).
 * This module is just the bootstrap path.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure) for the cross-cutting view.
 */
package com.stucray.limen.identity;

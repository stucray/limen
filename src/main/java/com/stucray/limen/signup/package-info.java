/**
 * Public self-service signup: the form anyone can fill in to create a new Tenant.
 *
 * <p>Holds {@code SignupController} (the {@code /signup} GET/POST endpoints),
 * {@code SignupForm} (the form-binding record), and {@code SignupService} (the
 * thin entry point that delegates to {@code TenantProvisioner} in the
 * {@code provisioning} module). This module *does not* own the orchestration
 * itself — it just renders the form and hands off.
 *
 * <p>Three "tenant lifecycle" entry points exist and are deliberately distinct:
 * <ul>
 *   <li>{@code signup} (this module) — public self-service form
 *   <li>{@code system} — System Admin equivalent ({@code /manage/system/tenants/new})
 *   <li>{@code provisioning} — the orchestrator both entry points call into
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure).
 */
package com.stucray.limen.signup;

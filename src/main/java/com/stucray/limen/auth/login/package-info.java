/**
 * Cross-module API for the login pipeline: the integration points other
 * modules hook into.
 *
 * <p>Three types are the public surface:
 * <ul>
 *   <li>{@link TenantUrlScheme} — per-surface URL conventions (login form
 *       match, slug extraction from any tenant-scoped URL, redirect
 *       templates). Exposed as a {@code @Bean} so each surface (OAuth2
 *       login, management login) registers its own scheme; integration
 *       tests can register synthetic ones without mocking.
 *   <li>{@link PostLoginIntent} — the strategy contract a module
 *       implements to act on a successful login (e.g. forced-password-change
 *       redirect). {@code PostLoginIntents} is the registry that runs
 *       them in order.
 *   <li>{@link TenantPasswordChangeFlow} — validation + persistence + the
 *       OAuth2-authorize-resume hand-off shared by both change-password
 *       surfaces. {@code PasswordChangeController} (also in this package)
 *       is the single controller that binds both URL prefixes and
 *       dispatches via {@code TenantUrlScheme.slugFrom(uri)}.
 * </ul>
 *
 * <p>Spring Modulith {@code @NamedInterface}; sibling sub-packages
 * ({@code auth.lockout}, {@code auth.ott}) are independent — see each
 * one's own {@code package-info} for details.
 */
@NamedInterface("login")
package com.stucray.limen.auth.login;

import org.springframework.modulith.NamedInterface;

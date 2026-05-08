/**
 * Tenant-aware authentication primitives shared across all login surfaces.
 *
 * <p>Holds {@code TenantAuthProvider} (the Spring Security {@code AuthenticationProvider}
 * that resolves a User within a Tenant), {@code TenantAuthToken} (the authenticated
 * principal type), {@code TenantUserDetailsService}, the persistent remember-me
 * adapter, and {@code TenantAccessFilter} (defence-in-depth: force-logout when the
 * URL slug differs from the principal's tenant). Sub-features live under
 * {@code auth.login} (the login pipeline + change-password orchestrator), {@code auth.ott}
 * (one-time-token issue/complete services for email verification + password reset),
 * and {@code auth.lockout} (account lockout state machine, internal).
 *
 * <p>Cross-module API: {@code auth.login} and {@code auth.ott} are exposed via
 * {@code @NamedInterface}. Other sub-packages are internal.
 *
 * <p>Not to be confused with {@code com.stucray.limen.oauth2}, which holds the
 * Spring Authorization Server integration (tenant-aware SAS decorators, routing
 * filter, JWK source). This module is "how a User authenticates"; {@code oauth2}
 * is "how OAuth2 protocol traffic is served."
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.5 (Authentication flows) for behaviour, §4.15 (Package structure) for the
 * cross-cutting view.
 */
package com.stucray.limen.auth;

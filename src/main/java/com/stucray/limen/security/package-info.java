/**
 * Foundation security infrastructure: global Spring Security defaults, signing-key
 * storage + rotation, and the rate-limit filter.
 *
 * <p>{@code DefaultSecurityConfig} owns the catch-all filter chain that every
 * request hits before more-specific chains (OAuth2, login, management) take over.
 * Per-Tenant RSA signing-key access is split into two cross-module public ports
 * by consumer role: {@code SigningKeyReader} (SAS sign + JWKS, consumed by
 * {@code oauth2.sas.TenantJwkSource}) and {@code SigningKeyProvisioning} (key
 * create/delete on tenant on/off-boarding, consumed by
 * {@code provisioning.TenantProvisioningService}). The third role —
 * {@code SigningKeyLifecycle} for rotation, pruning, eligibility scan — stays
 * package-private inside {@code security.signing} because its only consumer
 * ({@code SigningKeyRotator}) is co-located. The implementation cluster
 * ({@code JdbcSigningKeys}, {@code SigningKeyRotator}, scheduling, properties,
 * configuration) lives in that internal sub-package. {@code security.ratelimit}
 * is the in-memory token-bucket filter; it's also an internal sub-package.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.4 (Signing keys) and §4.9 (Rate limiting) for behaviour, §4.15 (Package
 * structure) for the cross-cutting view.
 */
package com.stucray.limen.security;

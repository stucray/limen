/**
 * Foundation security infrastructure: global Spring Security defaults, signing-key
 * store, signing-key rotation, and the rate-limit filter.
 *
 * <p>{@code DefaultSecurityConfig} owns the catch-all filter chain that every
 * request hits before more-specific chains (OAuth2, login, management) take over.
 * The {@code SigningKeyStore} interface is the cross-module API for per-Tenant
 * RSA signing-key storage; the implementation cluster ({@code JdbcSigningKeyStore},
 * {@code SigningKeyRotator}, scheduling, properties, configuration) lives in the
 * internal sub-package {@code security.signing}. The actual tenant-aware
 * {@code JWKSource} that consumes the store lives in {@code oauth2}.
 * {@code security.ratelimit} is the in-memory token-bucket filter; it's also an
 * internal sub-package.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.4 (Signing keys) and §4.9 (Rate limiting) for behaviour, §4.15 (Package
 * structure) for the cross-cutting view.
 */
package com.stucray.limen.security;

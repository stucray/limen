/**
 * Foundation security infrastructure: global Spring Security defaults, signing-key
 * store, signing-key rotation, and the rate-limit filter.
 *
 * <p>{@code DefaultSecurityConfig} owns the catch-all filter chain that every
 * request hits before more-specific chains (OAuth2, login, management) take over.
 * {@code JdbcSigningKeyStore} (and the {@code SigningKeyRotator} cluster) own
 * per-Tenant RSA signing-key persistence with envelope encryption — the actual
 * tenant-aware {@code JWKSource} that loads them lives in {@code oauth2}.
 * {@code security.ratelimit} is the in-memory token-bucket filter; it's an
 * internal sub-package, not callable from outside this module.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.4 (Signing keys) and §4.9 (Rate limiting) for behaviour, §4.15 (Package
 * structure) for the cross-cutting view.
 */
package com.stucray.limen.security;

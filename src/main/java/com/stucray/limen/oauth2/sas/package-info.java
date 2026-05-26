/**
 * Tenant-aware SAS persistence adapters, the SAS chain's server-error
 * translation filter, and the {@code @Configuration} that wires them.
 *
 * <p>Spring Authorization Server's storage SPIs are single-tenant. This package
 * supplies tenant-scoped implementations — some as decorators
 * ({@code TenantAwareRegisteredClientRepository}), some as delegate-then-UPDATE
 * ({@code TenantAwareOAuth2AuthorizationService}), some as direct SPI
 * implementations where the upstream Jdbc service cannot be cleanly decorated
 * ({@code TenantAwareOAuth2AuthorizationConsentService},
 * {@code TenantJwkSource}). All require an active
 * {@link com.stucray.limen.tenant.TenantScope} on calls that touch storage;
 * missing-scope semantics are per-adapter (see each class javadoc, and
 * {@code SasTenantScope}'s javadoc for which adapters share the hard-fail
 * helper).
 *
 * <p>{@code SasServerErrorTranslationFilter} sits at the head of the SAS
 * {@code SecurityFilterChain} and translates uncaught {@code RuntimeException}
 * on SAS endpoints into RFC 6749 §5.2 {@code server_error} JSON.
 * {@code OAuth2AuthenticationException} is intentionally allowed to propagate
 * so SAS's per-endpoint failure handlers still write the canonical
 * {@code invalid_grant} / {@code invalid_client} / etc. responses.
 *
 * <p>{@code LoopbackAwareOidcLogoutValidator} wraps SAS's default
 * post-logout-redirect-URI validator and adds a loopback fallback that mirrors
 * SAS's own {@code /oauth2/authorize} branch (RFC 8252 §7.3) on
 * {@code /connect/logout}. Restores {@code /authorize} ↔ {@code /logout}
 * symmetry for clients that rotate between local-dev ports.
 * {@code localhost} is intentionally not a loopback host (RFC 8252 §8.3 + SAS
 * issue #651). Wired via the configurer DSL in {@code SasConfig}.
 *
 * <p>{@code OidcScopeClaims} is the single source of truth for which OIDC
 * standard scopes Limen advertises in the discovery document and which claims
 * each one populates on the ID token. {@code SasConfig} reads it from two
 * places: the discovery customizer (emits {@code scopes_supported} and
 * {@code claims_supported}) and the ID-token branch of the JWT customizer
 * (populates claims when the corresponding scope is in the granted scope set).
 * The default Spring Authorization Server userinfo mapper filters the ID-token
 * claims by granted scope using the same standard OIDC mapping, so no separate
 * userinfo wiring is required to keep userinfo in lockstep.
 *
 * <p>Internal to {@code com.stucray.limen.oauth2}. The adapter types and the
 * filter are package-private; the rest of the application autowires the
 * Spring SPI interfaces published by {@code SasConfig}'s {@code @Bean}
 * methods. No {@code @NamedInterface} — Spring Modulith's default
 * sub-package-internal rule is the desired contract here, and
 * {@code ApplicationModules.verify()} locks it.
 */
package com.stucray.limen.oauth2.sas;

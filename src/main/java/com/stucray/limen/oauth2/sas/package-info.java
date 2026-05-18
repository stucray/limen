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
 * <p>Internal to {@code com.stucray.limen.oauth2}. The adapter types and the
 * filter are package-private; the rest of the application autowires the
 * Spring SPI interfaces published by {@code SasConfig}'s {@code @Bean}
 * methods. No {@code @NamedInterface} — Spring Modulith's default
 * sub-package-internal rule is the desired contract here, and
 * {@code ApplicationModules.verify()} locks it.
 */
package com.stucray.limen.oauth2.sas;

/**
 * Spring Authorization Server (SAS) integration: tenant-aware decorators, routing
 * filter, issuer-context filter, JWK source, and SAS configuration.
 *
 * <p>SAS by itself is single-tenant. This module makes it multi-tenant by
 * decorating every persistence interface SAS uses ({@code RegisteredClientRepository},
 * {@code OAuth2AuthorizationService}, {@code OAuth2AuthorizationConsentService})
 * with a tenant-scoping wrapper, supplying a tenant-aware {@code JWKSource} that
 * loads keys from {@code tenant_signing_key}, and bracketing every protocol
 * request with {@code TenantOAuth2RoutingFilter} +
 * {@code TenantIssuerContextFilter} so that the per-tenant issuer URL,
 * configuration document, and JWKS resolve correctly. {@code MembershipGateFilter}
 * enforces the eligibility/role checks on the authorize endpoint before SAS sees
 * the request.
 *
 * <p>Not to be confused with {@code com.stucray.limen.auth}, which holds the
 * non-OAuth2 authentication primitives (login, OTT, lockout, remember-me). This
 * module is "how OAuth2 protocol traffic is served"; {@code auth} is "how a User
 * authenticates."
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.2 (Request routing for OAuth2 traffic) and §4.3 (OAuth2 storage decorators)
 * for behaviour, §4.15 (Package structure) for the cross-cutting view.
 */
package com.stucray.limen.oauth2;

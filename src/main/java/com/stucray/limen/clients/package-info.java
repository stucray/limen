/**
 * {@code TenantClient} — Limen's multi-tenant decoration of a Spring Authorization
 * Server {@code RegisteredClient} — and its management UI.
 *
 * <p>Each Client belongs to exactly one Application within one Tenant. The pair is
 * persisted as one row in {@code client_metadata} (Limen-side: display name,
 * owning Application + Tenant) plus one row in {@code oauth2_registered_client}
 * (SAS-side: client id/secret, grant types, redirect URIs), joined 1:1 by foreign
 * key. The split keeps Limen's tenant model from leaking into Spring's schema.
 *
 * <p>Tenant Owners manage Clients under {@code /manage/t/{slug}/clients/...}; the
 * tenant-aware repository decorator that scopes SAS reads/writes lives in the
 * {@code oauth2} module.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.3 (OAuth2 storage decorators) and §4.15 (Package structure).
 */
package com.stucray.limen.clients;

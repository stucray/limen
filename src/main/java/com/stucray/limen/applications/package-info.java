/**
 * The {@code Application} entity and its per-Application CRUD service / controller.
 *
 * <p>An {@code Application} is a per-Tenant grouping of one or more OAuth2 Clients
 * plus the catalogue of Roles assignable inside it. Tenant Owners create
 * Applications under {@code /manage/t/{slug}/applications/...}; the Clients that
 * belong to an Application live in the {@code clients} module, the Roles in
 * {@code roles}, and the Memberships that grant Users access in {@code memberships}.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure) for the cross-cutting view of all top-level modules.
 */
package com.stucray.limen.applications;

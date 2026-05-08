/**
 * The per-Application {@code Role} catalogue and its management UI.
 *
 * <p>A {@code Role} row belongs to exactly one Application. The same row can be
 * assigned to a User as either an **App Role** (via {@code application_membership_role})
 * or a **Client Role** (via {@code client_membership_role}); only Client Roles
 * appear in the JWT {@code roles} claim. {@code RoleResolver} encapsulates the
 * look-up by Application + name used by both assignment paths.
 *
 * <p>Tenant Owners manage Roles under {@code /manage/t/{slug}/applications/{appId}/roles/...}.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.10 (Authorization — Roles, Memberships, and the JWT roles claim) and §4.15
 * (Package structure).
 */
package com.stucray.limen.roles;

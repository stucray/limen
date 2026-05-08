/**
 * {@code ApplicationMembership} + {@code ClientMembership} + Role-join entities,
 * with the queries and services that drive the per-Application and per-Client
 * members UI.
 *
 * <p>An {@code ApplicationMembership} is the eligibility gate: it grants a User
 * access to an Application (and zero-or-more App Roles for that Application).
 * A {@code ClientMembership} is the per-Client grant under that gate: it pins
 * Client Roles for a specific Client owned by the Application. Revoking
 * Application Membership cascades all Client Memberships under it (DB-level
 * {@code ON DELETE CASCADE}).
 *
 * <p>The two cross-cutting query objects ({@code UserMembershipPortfolioQuery},
 * {@code ClientMembershipQuery}) are how the User-detail page and the JWT
 * authority enricher see the world without N+1 round-trips.
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.10 (Authorization — Roles, Memberships, and the JWT roles claim) and §4.15
 * (Package structure).
 */
package com.stucray.limen.memberships;

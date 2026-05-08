/**
 * The {@code User} entity, {@code UserRepository}, and {@code TenantUserDetails}
 * (the Spring Security {@code UserDetails} adapter wrapping {@code User} +
 * {@code Tenant}).
 *
 * <p>{@code TenantUserDetails} lives here, *not* in {@code auth}, because audit,
 * auth, oauth2, management, and provisioning all need it; if it lived in
 * {@code auth}, audit's principal-extraction would create an
 * {@code audit ↔ auth} cycle. Co-locating it with the {@code User} entity matches
 * the conceptual mapping (user identity ⇒ user-as-principal) and lets every
 * consumer depend on {@code user} instead.
 *
 * <p>Not to be confused with {@code com.stucray.limen.useradmin}, which holds
 * Tenant-Owner administration of Users ({@code UserAdministrationService},
 * {@code PasswordChangeRequiredInterceptor}). This module is "what a User
 * <em>is</em>"; {@code useradmin} is "what an admin <em>does to</em> a User."
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §3 (Domain Model) and §4.15 (Package structure).
 */
package com.stucray.limen.user;

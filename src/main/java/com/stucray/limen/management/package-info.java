/**
 * Admin-console infrastructure: nav, login, home, model advice, and the admin
 * Spring Security filter chain config.
 *
 * <p>This module owns the *infrastructure* for the {@code /manage/t/{slug}/...}
 * surface — the management filter chain in {@code management.auth}, the MVC
 * configuration in {@code management.web}, the management home page, and shared
 * model advice. Domain features that *appear* under {@code /manage/...} URLs
 * (Applications, Clients, Roles, Memberships, Users) live in their own top-level
 * modules ({@code applications}, {@code clients}, {@code roles}, {@code memberships},
 * {@code useradmin}); each contributes its own {@code @Controller} that the
 * management filter chain protects.
 *
 * <p>Three "web-surface" modules exist and are deliberately distinct:
 * <ul>
 *   <li>{@code management} (this module) — {@code /manage/...} infrastructure
 *   <li>{@code enduser} — post-OAuth2 end-user pages
 *   <li>{@code web} — top-level routes ({@code /} landing, {@code /login} forwarder)
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.12 (HTTP route map) and §4.15 (Package structure).
 */
package com.stucray.limen.management;

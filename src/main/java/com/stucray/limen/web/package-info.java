/**
 * Top-level web routes: the landing page and the slug-aware {@code /login}
 * forwarder.
 *
 * <p>{@code RootController} renders {@code /} (the landing page with sign-in
 * and sign-up paths). {@code RedirectLoginController} handles {@code /login}:
 * if the request carries a tenant slug it forwards to
 * {@code /t/{slug}/login}; otherwise it renders the slug-input form.
 *
 * <p>Three "web-surface" modules exist and are deliberately distinct:
 * <ul>
 *   <li>{@code web} (this module) — top-level routes ({@code /}, {@code /login} forwarder)
 *   <li>{@code enduser} — post-OAuth2 end-user pages
 *   <li>{@code management} — admin-console infrastructure for {@code /manage/...}
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.12 (HTTP route map) and §4.15 (Package structure).
 */
package com.stucray.limen.web;

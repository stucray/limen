/**
 * End-user web routes — the surfaces an OAuth2 end-user lands on outside the
 * authorization-code flow.
 *
 * <p>Today this is the post-OAuth2 home page that an end-user sees after a
 * successful login when no client redirect is in play. Authentication for these
 * routes is handled by the OAuth2 login filter chain in {@code oauth2}; this
 * module just owns the post-login destination(s).
 *
 * <p>Three "web-surface" modules exist and are deliberately distinct:
 * <ul>
 *   <li>{@code enduser} (this module) — post-OAuth2 end-user pages
 *   <li>{@code web} — top-level routes ({@code /} landing, slug-aware {@code /login} forwarder)
 *   <li>{@code management} — admin-console infrastructure for {@code /manage/...}
 * </ul>
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.12 (HTTP route map) and §4.15 (Package structure).
 */
package com.stucray.limen.enduser;

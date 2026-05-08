/**
 * Tenant-Owner administration of Users within a Tenant.
 *
 * <p>Hosts {@code UserAdministrationService} (the service the admin console calls
 * when an Owner creates/disables/enables/unlocks/deletes a User, resets a
 * password, or grants/revokes Tenant Ownership), {@code UserManagementController}
 * (the {@code /manage/t/{slug}/users/...} surface), and
 * {@code PasswordChangeRequiredInterceptor} (the MVC interceptor that forces a
 * password change on the next management request when the User's
 * {@code must_change_password} flag is set).
 *
 * <p>The change-password form itself is hosted by
 * {@code com.stucray.limen.auth.login.PasswordChangeController} — a single
 * controller that serves both the OAuth2 surface and the management surface.
 * This module is admin-side actions only.
 *
 * <p>Not to be confused with {@code com.stucray.limen.user}, which holds the
 * {@code User} entity, {@code UserRepository}, and {@code TenantUserDetails}.
 * This module is "what an admin <em>does to</em> a User"; {@code user} is "what
 * a User <em>is</em>."
 *
 * <p>Spring Modulith application module. See {@code docs/reference/architecture.md}
 * §4.15 (Package structure) for the cross-cutting view.
 */
package com.stucray.limen.useradmin;

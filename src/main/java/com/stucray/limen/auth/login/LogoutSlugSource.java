package com.stucray.limen.auth.login;

/**
 * Which signal a surface's logout handler should read to recover the tenant
 * slug for the post-logout redirect. Explicit per-surface choice — a fallback
 * chain ("try URI first, then Referer") would hide which surface uses which
 * mechanism and let a future surface pick by accident.
 */
public enum LogoutSlugSource {
    /** Slug appears in the logout request URI (e.g. {@code /t/{slug}/logout}). */
    REQUEST_URI,
    /** Logout endpoint is slugless (e.g. {@code /manage/logout}); slug is read from the {@code Referer} header. */
    REFERER_HEADER
}

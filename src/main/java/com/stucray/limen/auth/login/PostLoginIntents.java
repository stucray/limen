package com.stucray.limen.auth.login;

import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;

/**
 * Static factories for the three default {@link PostLoginIntent}s wired by
 * {@link TenantLoginAutoConfig}. Each is independently testable.
 *
 * Default chain order (terminal-last):
 * <ol>
 *   <li>{@link #passwordChangeRequired()} — redirect when {@code mustChangePassword} is set.</li>
 *   <li>{@link #resumeOAuth2Authorize()} — consume a saved {@code /oauth2/authorize} request.</li>
 *   <li>{@link #tenantHome()} — terminal default; always returns the tenant home URL.</li>
 * </ol>
 *
 * The password-change check fires <em>before</em> OAuth2-resume so a user with an
 * expired password cannot complete an authorize flow before updating their password.
 */
public final class PostLoginIntents {

    private PostLoginIntents() {}

    public static PostLoginIntent passwordChangeRequired() {
        return (req, res, principal, scheme) -> principal.mustChangePassword()
            ? scheme.changePasswordUrl(principal.tenantSlug())
            : null;
    }

    public static PostLoginIntent resumeOAuth2Authorize() {
        return resumeOAuth2Authorize(new HttpSessionRequestCache());
    }

    /** Test seam: inject a request cache. */
    static PostLoginIntent resumeOAuth2Authorize(RequestCache requestCache) {
        return (req, res, principal, scheme) -> {
            SavedRequest saved = requestCache.getRequest(req, res);
            if (saved == null || !saved.getRedirectUrl().contains("/oauth2/authorize")) {
                return null;
            }
            requestCache.removeRequest(req, res);
            return prependTenantPrefix(saved.getRedirectUrl(), principal.tenantSlug());
        };
    }

    public static PostLoginIntent tenantHome() {
        return (req, res, principal, scheme) -> scheme.homeUrl(principal.tenantSlug());
    }

    private static String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

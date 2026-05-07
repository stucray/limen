package com.stucray.limen.auth.login;

import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.TenantOttAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;

/**
 * Static factories for the default {@link PostLoginIntent}s wired by
 * {@link TenantLoginAutoConfig}. Each is independently testable.
 *
 * Default chain order (terminal-last):
 * <ol>
 *   <li>{@link #emailVerificationRequired()} — redirect to check-inbox when the
 *       just-authenticated user has not yet verified their email. Sits ahead of
 *       OAuth2-resume so an unverified principal cannot complete an authorize
 *       flow before clicking the magic link.</li>
 *   <li>{@link #passwordChangeAfterReset()} — redirect to change-password when
 *       the just-authenticated session is a {@link TenantOttAuthentication} with
 *       {@code intent=PASSWORD_RESET}. Ahead of OAuth2-resume so a reset
 *       interrupts any saved authorize.</li>
 *   <li>{@link #passwordChangeRequired()} — redirect when {@code mustChangePassword} is set.</li>
 *   <li>{@link #resumeOAuth2Authorize()} — consume a saved {@code /oauth2/authorize} request.</li>
 *   <li>{@link #tenantHome()} — terminal default; always returns the tenant home URL.</li>
 * </ol>
 *
 * The password-change checks fire <em>before</em> OAuth2-resume so a user with an
 * expired password — or one mid password-reset — cannot complete an authorize
 * flow before updating their password.
 */
public final class PostLoginIntents {

    private PostLoginIntents() {}

    /**
     * If the principal has {@code email_verified=false}, redirect to the
     * tenant's check-inbox page so a fresh verification email can be requested.
     * The OTT consume path itself flips the flag to {@code true} via
     * {@code OttCompletionService.markEmailVerified}, so a successful
     * verify-email login falls through to the next intent in the chain.
     */
    public static PostLoginIntent emailVerificationRequired() {
        return (req, res, principal, scheme) -> principal.user().emailVerified()
            ? null
            : "/t/" + principal.tenantSlug() + "/check-inbox";
    }

    public static PostLoginIntent passwordChangeRequired() {
        return (req, res, principal, scheme) -> principal.mustChangePassword()
            ? scheme.changePasswordUrl(principal.tenantSlug())
            : null;
    }

    /**
     * If the current {@code Authentication} is a {@link TenantOttAuthentication}
     * carrying {@link OttIntent#PASSWORD_RESET}, route to change-password. The
     * intent rides on the authentication itself (persisted across requests via
     * the security context), so a reload of the change-password form keeps
     * routing here until {@code TenantPasswordChangeFlow} rotates the context
     * to a plain authenticated principal on successful submission.
     */
    public static PostLoginIntent passwordChangeAfterReset() {
        return (req, res, principal, scheme) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth instanceof TenantOttAuthentication tott
                && tott.intent() == OttIntent.PASSWORD_RESET
                    ? scheme.changePasswordUrl(principal.tenantSlug())
                    : null;
        };
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

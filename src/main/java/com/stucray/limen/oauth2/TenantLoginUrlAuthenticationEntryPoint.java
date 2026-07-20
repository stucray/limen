package com.stucray.limen.oauth2;

import com.stucray.limen.auth.login.PendingAuthorizeStore;
import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redirects unauthenticated requests to the matching tenant's `/t/{slug}/login`,
 * falling back to `/manage/t/system/login` when no slug can be resolved.
 *
 * The slug-resolution strategy is constructor-injected because the SAS chain runs
 * after the URL-strip wrapper (so the request URI no longer contains the slug —
 * the routing filter's bound TenantScope is the only carrier left), while the
 * OAuth2-login chain at Order(1) runs against the original URL.
 *
 * <p>When constructed with a {@link PendingAuthorizeStore} (the SAS-chain
 * variant), an unauthenticated {@code /oauth2/authorize} bounce also stashes the
 * pending request durably and carries an opaque {@code ?ref=} on the login URL,
 * so {@link PendingAuthorizeStore#consume} can replay the flow after the HTTP
 * session's in-session SavedRequest is evicted (issue #327). Other bounces (and
 * the {@code fromUrl()} variant, which has no store) redirect unchanged.
 */
public class TenantLoginUrlAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^/t/([^/]+)/.*");

    private final Function<HttpServletRequest, @Nullable String> slugResolver;
    private final @Nullable PendingAuthorizeStore pendingAuthorizeStore;

    TenantLoginUrlAuthenticationEntryPoint(
        Function<HttpServletRequest, @Nullable String> slugResolver,
        @Nullable PendingAuthorizeStore pendingAuthorizeStore
    ) {
        super("/manage/t/system/login");
        this.slugResolver = slugResolver;
        this.pendingAuthorizeStore = pendingAuthorizeStore;
    }

    /** Resolves the slug from the un-stripped request URL (e.g. for the OAuth2-login chain). */
    static TenantLoginUrlAuthenticationEntryPoint fromUrl() {
        return new TenantLoginUrlAuthenticationEntryPoint(req -> {
            Matcher m = SLUG_PATTERN.matcher(req.getRequestURI());
            return m.matches() ? m.group(1) : null;
        }, null);
    }

    /**
     * Resolves the slug from the routing filter's TenantScope binding (e.g. for
     * the SAS chain), and durably stashes an unauthenticated {@code /oauth2/authorize}
     * bounce via the supplied {@link PendingAuthorizeStore}.
     */
    public static TenantLoginUrlAuthenticationEntryPoint fromTenantScope(
        PendingAuthorizeStore pendingAuthorizeStore
    ) {
        return new TenantLoginUrlAuthenticationEntryPoint(
            req -> TenantScope.slug(), pendingAuthorizeStore);
    }

    @Override
    protected String determineUrlToUseForThisRequest(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) {
        String slug = slugResolver.apply(request);
        if (slug == null) {
            return "/manage/t/system/login";
        }
        String loginUrl = "/t/" + slug + "/login";
        if (pendingAuthorizeStore != null && isAuthorizeRequest(request)) {
            // Stash the pending /oauth2/authorize durably and carry an opaque
            // single-use reference on the login URL (issue #327), so the flow
            // can be replayed after the in-session SavedRequest is evicted.
            String ref = pendingAuthorizeStore.stash(slug, authorizeUrlOf(request));
            loginUrl = loginUrl + "?ref=" + ref;
        }
        return loginUrl;
    }

    private static boolean isAuthorizeRequest(HttpServletRequest request) {
        return request.getRequestURI().contains("/oauth2/authorize");
    }

    private static String authorizeUrlOf(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}

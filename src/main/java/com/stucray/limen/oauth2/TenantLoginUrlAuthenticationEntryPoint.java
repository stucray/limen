package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
public class TenantLoginUrlAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^/t/([^/]+)/.*");

    private final Function<HttpServletRequest, String> slugResolver;

    public TenantLoginUrlAuthenticationEntryPoint(Function<HttpServletRequest, String> slugResolver) {
        super("/manage/t/system/login");
        this.slugResolver = slugResolver;
    }

    /** Resolves the slug from the un-stripped request URL (e.g. for the OAuth2-login chain). */
    public static TenantLoginUrlAuthenticationEntryPoint fromUrl() {
        return new TenantLoginUrlAuthenticationEntryPoint(req -> {
            Matcher m = SLUG_PATTERN.matcher(req.getRequestURI());
            return m.matches() ? m.group(1) : null;
        });
    }

    /** Resolves the slug from the routing filter's TenantScope binding (e.g. for the SAS chain). */
    public static TenantLoginUrlAuthenticationEntryPoint fromTenantScope() {
        return new TenantLoginUrlAuthenticationEntryPoint(req -> TenantScope.slug());
    }

    @Override
    protected String determineUrlToUseForThisRequest(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) {
        String slug = slugResolver.apply(request);
        return slug != null ? "/t/" + slug + "/login" : "/manage/t/system/login";
    }
}

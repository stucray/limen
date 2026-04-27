package com.stucray.limen.oauth2;

import com.stucray.limen.auth.TenantUserDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.IOException;
import java.net.URI;

public class TenantLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request, HttpServletResponse response, Authentication auth
    ) throws IOException, ServletException {
        TenantUserDetails principal = (TenantUserDetails) auth.getPrincipal();
        String slug = principal.tenantSlug();

        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null && savedRequest.getRedirectUrl().contains("/oauth2/authorize")) {
            if (principal.mustChangePassword()) {
                // Leave SavedRequest in cache so the change-password POST can resume the OAuth2 flow
                getRedirectStrategy().sendRedirect(request, response,
                    "/t/" + slug + "/change-password");
                return;
            }
            requestCache.removeRequest(request, response);
            clearAuthenticationAttributes(request);
            getRedirectStrategy().sendRedirect(request, response,
                prependTenantPrefix(savedRequest.getRedirectUrl(), slug));
            return;
        }
        // No OAuth2 saved request — land the user on their tenant's home page.
        getRedirectStrategy().sendRedirect(request, response, "/t/" + slug + "/");
    }

    private String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

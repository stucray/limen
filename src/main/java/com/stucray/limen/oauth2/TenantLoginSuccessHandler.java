package com.stucray.limen.oauth2;

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
        String slug = (String) request.getSession().getAttribute("OAUTH2_TENANT_SLUG");
        if (slug != null) {
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null && savedRequest.getRedirectUrl().contains("/oauth2/authorize")) {
                requestCache.removeRequest(request, response);
                clearAuthenticationAttributes(request);
                getRedirectStrategy().sendRedirect(request, response,
                    prependTenantPrefix(savedRequest.getRedirectUrl(), slug));
                return;
            }
        }
        super.onAuthenticationSuccess(request, response, auth);
    }

    private String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

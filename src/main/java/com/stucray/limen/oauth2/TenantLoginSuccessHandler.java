package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
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
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    public TenantLoginSuccessHandler(UserRepository userRepository, TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request, HttpServletResponse response, Authentication auth
    ) throws IOException, ServletException {
        String slug = (String) request.getSession().getAttribute("OAUTH2_TENANT_SLUG");
        if (slug != null) {
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null && savedRequest.getRedirectUrl().contains("/oauth2/authorize")) {
                if (mustChangePassword(auth.getName(), slug)) {
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
        }
        super.onAuthenticationSuccess(request, response, auth);
    }

    private boolean mustChangePassword(String username, String slug) {
        return tenantRepository.findBySlug(slug)
            .flatMap(tenant -> userRepository.findByUsernameAndTenantId(username, tenant.id()))
            .map(user -> user.mustChangePassword())
            .orElse(false);
    }

    private String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

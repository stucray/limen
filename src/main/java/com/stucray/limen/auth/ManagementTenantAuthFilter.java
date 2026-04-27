package com.stucray.limen.auth;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public final class ManagementTenantAuthFilter extends AbstractTenantAuthFilter {

    public ManagementTenantAuthFilter(AuthenticationManager authenticationManager) {
        super(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/manage/t/*/login"),
            authenticationManager,
            req -> extractSlug(req.getRequestURI())
        );

        AuthenticationSuccessHandler success = (req, res, auth) -> {
            String slug = ((TenantUserDetails) auth.getPrincipal()).tenantSlug();
            res.sendRedirect(req.getContextPath() + "/manage/t/" + slug + "/");
        };
        AuthenticationFailureHandler failure = (req, res, ex) -> {
            String slug = extractSlug(req.getRequestURI());
            res.sendRedirect(req.getContextPath() + "/manage/t/" + slug + "/login?error");
        };
        setAuthenticationSuccessHandler(success);
        setAuthenticationFailureHandler(failure);
    }

    private static String extractSlug(String uri) {
        // /manage/t/{slug}/login → parts: ["", "manage", "t", "{slug}", "login"]
        String[] parts = uri.split("/");
        return parts[3];
    }
}

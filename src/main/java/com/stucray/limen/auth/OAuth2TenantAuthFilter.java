package com.stucray.limen.auth;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public final class OAuth2TenantAuthFilter extends AbstractTenantAuthFilter {

    public OAuth2TenantAuthFilter(
        AuthenticationManager authenticationManager,
        AuthenticationSuccessHandler successHandler
    ) {
        super(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/t/*/login"),
            authenticationManager,
            req -> extractSlug(req.getRequestURI())
        );

        AuthenticationFailureHandler failure = (req, res, ex) -> {
            String slug = extractSlug(req.getRequestURI());
            res.sendRedirect(req.getContextPath() + "/t/" + slug + "/login?error");
        };
        setAuthenticationSuccessHandler(successHandler);
        setAuthenticationFailureHandler(failure);
    }

    private static String extractSlug(String uri) {
        // /t/{slug}/login → parts: ["", "t", "{slug}", "login"]
        String[] parts = uri.split("/");
        return parts[2];
    }
}

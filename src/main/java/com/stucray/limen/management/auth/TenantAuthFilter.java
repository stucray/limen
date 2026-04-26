package com.stucray.limen.management.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;

public class TenantAuthFilter extends AbstractAuthenticationProcessingFilter {

    public TenantAuthFilter(AuthenticationManager authenticationManager) {
        super(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/manage/t/*/login"),
            authenticationManager
        );
        setAuthenticationSuccessHandler((req, res, auth) -> {
            String slug = extractSlug(req.getRequestURI());
            res.sendRedirect(req.getContextPath() + "/manage/t/" + slug + "/");
        });
        setAuthenticationFailureHandler((req, res, ex) -> {
            String slug = extractSlug(req.getRequestURI());
            res.sendRedirect(req.getContextPath() + "/manage/t/" + slug + "/login?error");
        });
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
        throws AuthenticationException, IOException {
        String slug = extractSlug(request.getRequestURI());
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        return getAuthenticationManager().authenticate(new TenantAuthToken(slug, username, password));
    }

    private static String extractSlug(String uri) {
        // /manage/t/{slug}/login → parts: ["", "manage", "t", "{slug}", "login"]
        String[] parts = uri.split("/");
        return parts[3];
    }
}

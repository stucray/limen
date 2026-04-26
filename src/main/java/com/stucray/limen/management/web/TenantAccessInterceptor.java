package com.stucray.limen.management.web;

import com.stucray.limen.management.auth.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects requests where the URL tenant slug does not match the authenticated user's tenant.
 * Prevents a user authenticated for tenant A from accessing tenant B's management pages.
 */
public class TenantAccessInterceptor implements HandlerInterceptor {

    private static final Pattern SLUG_PATTERN = Pattern.compile("/manage/t/([^/]+)/.*");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        Matcher matcher = SLUG_PATTERN.matcher(uri);
        if (!matcher.matches()) return true;

        String urlSlug = matcher.group(1);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;

        if (auth.getPrincipal() instanceof TenantUserDetails details) {
            if (!details.tenantSlug().equals(urlSlug)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }
        return true;
    }
}

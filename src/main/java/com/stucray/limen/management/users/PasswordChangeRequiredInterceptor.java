package com.stucray.limen.management.users;

import com.stucray.limen.management.auth.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Redirects users with mustChangePassword=true to the password change form
 * before they can access any management console page.
 */
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        if (!(auth.getPrincipal() instanceof TenantUserDetails details)) return true;
        if (!details.mustChangePassword()) return true;

        String uri = request.getRequestURI();
        String changePasswordUrl = "/manage/t/" + details.tenantSlug() + "/change-password";

        // Allow access to the change-password page and logout to avoid redirect loops
        if (uri.equals(changePasswordUrl) || uri.endsWith("/logout")) return true;

        response.sendRedirect(changePasswordUrl);
        return false;
    }
}

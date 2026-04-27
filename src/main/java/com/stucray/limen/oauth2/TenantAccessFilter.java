package com.stucray.limen.oauth2;

import com.stucray.limen.auth.TenantUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defence-in-depth: when an authenticated request's URL slug differs from the
 * principal's tenant slug, force-logout (clear context, invalidate session) and
 * redirect to the URL slug's login page. Covers both `/t/{slug}/...` and
 * `/manage/t/{slug}/...`.
 *
 * Must be installed inside the Spring Security filter chain, AFTER the
 * SecurityContextHolderFilter — otherwise SecurityContextHolder is empty and
 * the cross-tenant mismatch isn't detectable.
 */
public final class TenantAccessFilter extends OncePerRequestFilter {

    private static final Pattern OAUTH2_TENANT = Pattern.compile("^/t/([^/]+)/.*");
    private static final Pattern MANAGEMENT_TENANT = Pattern.compile("^/manage/t/([^/]+)/.*");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String urlSlug;
        String loginPath;

        Matcher mgmtMatch = MANAGEMENT_TENANT.matcher(uri);
        Matcher oauthMatch = OAUTH2_TENANT.matcher(uri);
        if (mgmtMatch.matches()) {
            urlSlug = mgmtMatch.group(1);
            loginPath = "/manage/t/" + urlSlug + "/login";
        } else if (oauthMatch.matches()) {
            urlSlug = oauthMatch.group(1);
            loginPath = "/t/" + urlSlug + "/login";
        } else {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
            && auth.getPrincipal() instanceof TenantUserDetails details
            && !urlSlug.equals(details.tenantSlug())
            // Skip the login endpoints themselves so a stale session can re-authenticate at the URL slug
            && !uri.equals(loginPath)) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            response.sendRedirect(request.getContextPath() + loginPath);
            return;
        }

        chain.doFilter(request, response);
    }
}

package com.stucray.limen.oauth2;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.auth.login.TenantUrlScheme;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Defence-in-depth: when an authenticated request's URL slug differs from the
 * principal's tenant slug, force-logout (clear context, invalidate session) and
 * redirect to the URL slug's login page.
 *
 * The set of recognized URL surfaces is derived from the registered
 * {@link TenantUrlScheme} beans — adding a new login surface (or registering a
 * synthetic one in tests) extends cross-tenant defence to that surface
 * automatically.
 *
 * Must be installed inside the Spring Security filter chain, AFTER the
 * SecurityContextHolderFilter — otherwise SecurityContextHolder is empty and
 * the cross-tenant mismatch isn't detectable.
 */
public final class TenantAccessFilter extends OncePerRequestFilter {

    private final List<TenantUrlScheme> schemes;

    public TenantAccessFilter(List<TenantUrlScheme> schemes) {
        this.schemes = schemes;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        TenantUrlScheme matched = null;
        String urlSlug = null;
        for (TenantUrlScheme scheme : schemes) {
            String slug = scheme.slugFrom(uri);
            if (slug != null) {
                matched = scheme;
                urlSlug = slug;
                break;
            }
        }
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        String loginPath = matched.loginUrl(urlSlug);
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

package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts requests to /t/{slug}/oauth2/**, /t/{slug}/.well-known/**, /t/{slug}/connect/**,
 * and /t/{slug}/userinfo, validates and resolves the tenant, binds it on a TenantScope,
 * then strips the /t/{slug} prefix so Spring Authorization Server sees its standard
 * endpoint paths. TenantIssuerContextFilter (registered inside the SAS security chain)
 * then sets the per-request AuthorizationServerContext with the correct tenant
 * issuer URL.
 *
 * /t/{slug}/login and /t/{slug}/change-password are intentionally NOT matched: the
 * OAuth2-login filter chain (Order 1) owns those paths and processes them without
 * URL-strip so the slug is visible to the authentication backend.
 */
@Component
@Order(Integer.MIN_VALUE + 10)
public class TenantOAuth2RoutingFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_PATH =
        Pattern.compile("^/t/([^/]+)/((oauth2|\\.well-known|connect)/.*|userinfo)$");

    private final TenantRepository tenantRepository;

    public TenantOAuth2RoutingFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        Matcher m = TENANT_PATH.matcher(uri);

        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }

        String slug = m.group(1);
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);

        if (tenant == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown tenant: " + slug);
            return;
        }
        if (tenant.status() == TenantStatus.SUSPENDED) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant is suspended");
            return;
        }

        try {
            TenantScope.call(slug, tenant.id(), () -> {
                chain.doFilter(new TenantOAuth2RequestWrapper(request, slug), response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}

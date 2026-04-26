package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
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
 * Intercepts requests to /t/{slug}/oauth2/** and /t/{slug}/.well-known/**,
 * validates and resolves the tenant, stores it in TenantContext, then strips
 * the /t/{slug} prefix so Spring Authorization Server sees its standard endpoint
 * paths. TenantIssuerContextFilter (registered inside the SAS security chain)
 * then sets the per-request AuthorizationServerContext with the correct tenant
 * issuer URL.
 */
@Component
@Order(Integer.MIN_VALUE + 10)
public class TenantOAuth2RoutingFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_PATH =
        Pattern.compile("^/t/([^/]+)/((oauth2|\\.well-known|connect)/.*|login|userinfo)$");

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

        TenantContext.set(slug, tenant.id());
        request.getSession(true).setAttribute("OAUTH2_TENANT_SLUG", slug);
        try {
            chain.doFilter(new TenantOAuth2RequestWrapper(request, slug), response);
        } finally {
            TenantContext.clear();
        }
    }
}

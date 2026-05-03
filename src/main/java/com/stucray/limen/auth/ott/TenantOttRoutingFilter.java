package com.stucray.limen.auth.ott;

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
 * Binds {@link TenantScope} for the OTT URLs ({@code /t/{slug}/login/ott},
 * {@code /t/{slug}/check-inbox}, {@code /t/{slug}/resend-verification}) so the
 * OTT auth provider, the tenant-aware token service, and the controllers below
 * can read tenant identity from {@link TenantScope} instead of plumbing it
 * through every method signature.
 *
 * <p>Modelled on {@code TenantOAuth2RoutingFilter} but does <em>not</em> strip
 * the URL prefix — these endpoints are owned by Limen's own controllers and
 * filters, which expect to see the slug.
 *
 * <p>Runs at {@code MIN_VALUE + 11} so it is sequenced after
 * {@code TenantOAuth2RoutingFilter} (which holds {@code MIN_VALUE + 10} and is
 * already responsible for the {@code /oauth2/...} prefix-strip) and well before
 * any of the security-chain filters.
 */
@Component
@Order(Integer.MIN_VALUE + 11)
public class TenantOttRoutingFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_OTT_PATH =
        Pattern.compile("^/t/([^/]+)/(login/ott|check-inbox|resend-verification)(?:[/?].*)?$");

    private final TenantRepository tenantRepository;

    public TenantOttRoutingFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        Matcher m = TENANT_OTT_PATH.matcher(uri);
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
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}

package com.stucray.limen.observability;

import com.stucray.limen.tenant.TenantScope;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Decorates the active request with tenant attribution for observability:
 * sets {@code tenant.id} / {@code tenant.slug} on the current OpenTelemetry
 * span (queryable in Tempo) and on the SLF4J MDC (emitted as structured
 * fields on OTLP-exported logs in Loki, and visible in console log lines).
 *
 * Runs after the tenant-binding filters ({@code TenantOAuth2RoutingFilter},
 * {@code TenantOttRoutingFilter}, both at {@code MIN_VALUE + 10}), so
 * {@link TenantScope} is already populated for tenant-scoped paths. On
 * non-tenant paths ({@code /}, {@code /login}, {@code /actuator/health})
 * {@code TenantScope} is unbound and this filter is a no-op.
 *
 * Cardinality safety: the slug is set as a span attribute and a log MDC
 * value, NEVER as a metric tag — Tempo and Loki structured fields don't
 * pay the cardinality cost that Prometheus/Mimir labels would.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
final class TenantObservabilityFilter extends OncePerRequestFilter {

    private static final String MDC_TENANT_SLUG = "tenant.slug";
    private static final String MDC_TENANT_ID = "tenant.id";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String slug = TenantScope.slug();
        Long tenantId = TenantScope.tenantId();

        if (slug == null) {
            chain.doFilter(request, response);
            return;
        }

        Span span = Span.current();
        span.setAttribute("tenant.slug", slug);
        if (tenantId != null) span.setAttribute("tenant.id", tenantId);

        MDC.put(MDC_TENANT_SLUG, slug);
        if (tenantId != null) MDC.put(MDC_TENANT_ID, tenantId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TENANT_SLUG);
            MDC.remove(MDC_TENANT_ID);
        }
    }
}

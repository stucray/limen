package com.stucray.limen.auth.ott;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

/**
 * Builds the absolute OTT magic-link URL the user clicks in a verification or
 * password-reset email. Base URL (scheme, host, port, context path) is derived
 * from the inbound servlet request the same way
 * {@link com.stucray.limen.oauth2.TenantIssuerContextFilter} derives the
 * per-tenant {@code iss} claim, so dev / staging / prod each produce a URL
 * that round-trips back to the same Limen instance without per-environment
 * config.
 *
 * <p>Requires an active servlet request to resolve the base URL. Callers in
 * production are the controllers that issue OTTs (signup, resend,
 * forgot-password), so this invariant holds. Tests outside a request context
 * must set up a mock via
 * {@code RequestContextHolder.setRequestAttributes(...)}.
 */
@Component
class MagicLinkBuilder {

    String build(String tenantSlug, String tokenValue) {
        HttpServletRequest request = ((ServletRequestAttributes) Objects.requireNonNull(
            RequestContextHolder.getRequestAttributes(),
            "MagicLinkBuilder requires an active servlet request")).getRequest();
        return baseUrl(request) + "/t/" + tenantSlug + "/login/ott?token=" + tokenValue;
    }

    private static String baseUrl(HttpServletRequest request) {
        int port = request.getServerPort();
        String scheme = request.getScheme();
        boolean isDefault = ("http".equals(scheme) && port == 80)
            || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName()
            + (isDefault ? "" : ":" + port) + request.getContextPath();
    }
}

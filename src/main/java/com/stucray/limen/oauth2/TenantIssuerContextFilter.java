package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs inside the SAS security filter chain, after AuthorizationServerContextFilter has
 * populated AuthorizationServerContextHolder with the default (non-tenant) issuer. For
 * tenant-routed requests, overwrites the context with the correct per-tenant issuer URL
 * so that discovery documents and tokens carry the right issuer claim.
 */
public class TenantIssuerContextFilter extends OncePerRequestFilter {

    private final AuthorizationServerSettings baseSettings;

    TenantIssuerContextFilter(AuthorizationServerSettings baseSettings) {
        this.baseSettings = baseSettings;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String slug = TenantScope.slug();
        if (slug != null) {
            String issuer = buildBaseUrl(request) + "/t/" + slug;
            AuthorizationServerSettings tenantSettings = AuthorizationServerSettings.builder()
                .issuer(issuer)
                .authorizationEndpoint(baseSettings.getAuthorizationEndpoint())
                .pushedAuthorizationRequestEndpoint(baseSettings.getPushedAuthorizationRequestEndpoint())
                .deviceAuthorizationEndpoint(baseSettings.getDeviceAuthorizationEndpoint())
                .deviceVerificationEndpoint(baseSettings.getDeviceVerificationEndpoint())
                .tokenEndpoint(baseSettings.getTokenEndpoint())
                .tokenRevocationEndpoint(baseSettings.getTokenRevocationEndpoint())
                .tokenIntrospectionEndpoint(baseSettings.getTokenIntrospectionEndpoint())
                .jwkSetEndpoint(baseSettings.getJwkSetEndpoint())
                .oidcLogoutEndpoint(baseSettings.getOidcLogoutEndpoint())
                .oidcUserInfoEndpoint(baseSettings.getOidcUserInfoEndpoint())
                .oidcClientRegistrationEndpoint(baseSettings.getOidcClientRegistrationEndpoint())
                .build();
            AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
                @Override public String getIssuer() { return issuer; }
                @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return tenantSettings; }
            });
        }
        chain.doFilter(request, response);
    }

    private static String buildBaseUrl(HttpServletRequest request) {
        int port = request.getServerPort();
        String scheme = request.getScheme();
        boolean isDefault = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (isDefault ? "" : ":" + port) + request.getContextPath();
    }
}

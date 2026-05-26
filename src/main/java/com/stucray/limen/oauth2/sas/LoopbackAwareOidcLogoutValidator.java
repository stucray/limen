package com.stucray.limen.oauth2.sas;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationValidator;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Consumer;

/**
 * Mirrors Spring Authorization Server's {@code /oauth2/authorize} loopback
 * port-wildcarding branch (RFC 8252 §7.3) on {@code /connect/logout}. Restores
 * symmetry: a registered post-logout URI on one loopback port matches a
 * requested post-logout URI on a different loopback port, the same way a
 * registered redirect URI does at {@code /oauth2/authorize}.
 *
 * <p>Wraps SAS's default
 * {@link OidcLogoutAuthenticationValidator#DEFAULT_POST_LOGOUT_REDIRECT_URI_VALIDATOR}.
 * If the default rejects AND the requested URI's host is a loopback address,
 * retries the match by substituting the requested port into each registered URI
 * and string-comparing (mirroring SAS's mechanism exactly). Scheme is preserved
 * transitively by the string compare — HTTPS-registered + HTTPS-requested
 * matches, but a scheme mismatch fails.
 *
 * <p>{@code localhost} is intentionally NOT a loopback host (RFC 8252 §8.3 +
 * SAS issue #651). Use {@code 127.0.0.1} for loopback URIs.
 *
 * <p>See PRD #316; slice #318.
 */
final class LoopbackAwareOidcLogoutValidator implements Consumer<OidcLogoutAuthenticationContext> {

    private final Consumer<OidcLogoutAuthenticationContext> delegate;

    LoopbackAwareOidcLogoutValidator() {
        this(OidcLogoutAuthenticationValidator.DEFAULT_POST_LOGOUT_REDIRECT_URI_VALIDATOR);
    }

    LoopbackAwareOidcLogoutValidator(Consumer<OidcLogoutAuthenticationContext> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void accept(OidcLogoutAuthenticationContext context) {
        try {
            delegate.accept(context);
        } catch (OAuth2AuthenticationException ex) {
            if (!loopbackBranchMatches(context)) {
                throw ex;
            }
        }
    }

    private static boolean loopbackBranchMatches(OidcLogoutAuthenticationContext context) {
        OidcLogoutAuthenticationToken token = context.getAuthentication();
        String requestedUri = token.getPostLogoutRedirectUri();
        if (requestedUri == null || requestedUri.isBlank()) {
            return false;
        }
        UriComponents requested;
        try {
            requested = UriComponentsBuilder.fromUriString(requestedUri).build();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!isLoopbackAddress(requested.getHost())) {
            return false;
        }
        RegisteredClient registeredClient = context.getRegisteredClient();
        for (String registered : registeredClient.getPostLogoutRedirectUris()) {
            UriComponentsBuilder rebuilt = UriComponentsBuilder.fromUriString(registered);
            rebuilt.port(requested.getPort());
            if (rebuilt.build().toString().equals(requested.toString())) {
                return true;
            }
        }
        return false;
    }

    // Mirrors OAuth2AuthorizationCodeRequestAuthenticationValidator#isLoopbackAddress
    // in spring-security-oauth2-authorization-server. 127.0.0.1/8 plus the two
    // literal IPv6 loopback spellings; localhost is NOT loopback.
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition") // IPv4 octet count is structural, not arbitrary
    private static boolean isLoopbackAddress(@Nullable String host) {
        if (host == null) {
            return false;
        }
        if ("[0:0:0:0:0:0:0:1]".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        String[] octets = host.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int[] addr = new int[4];
            for (int i = 0; i < 4; i++) {
                addr[i] = Integer.parseInt(octets[i]);
            }
            return addr[0] == 127
                && addr[1] >= 0 && addr[1] <= 255
                && addr[2] >= 0 && addr[2] <= 255
                && addr[3] >= 1 && addr[3] <= 255;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}

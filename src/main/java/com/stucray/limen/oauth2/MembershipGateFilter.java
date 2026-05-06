package com.stucray.limen.oauth2;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.memberships.ClientMembershipQuery;
import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Enforces Client Membership at /oauth2/authorize. Sits inside the Spring
 * Authorization Server security chain after the SAS pre-validation filter and
 * before {@code OAuth2AuthorizationEndpointFilter}, so the request shape and
 * {@code redirect_uri} are already validated by the time we run.
 *
 * <p>End-User Login at /t/&#123;slug&#125;/login is unchanged; credentials
 * still validate against the Tenant's User pool. The gate sits one step
 * downstream so authentication and authorization remain separable failures —
 * an authenticated User without a {@code client_membership} row for the
 * Client is rejected with {@code error=access_denied} per RFC 6749 §4.1.2.1.
 *
 * <p>Membership presence (not Role count) is the gate: a User with a Client
 * Membership and zero Roles passes (the JWT carries {@code roles: []}).
 *
 * <p>Implementation note — this is a Filter rather than an
 * {@code AuthenticationProvider} decorator on
 * {@code OAuth2AuthorizationCodeRequestAuthenticationProvider}: SAS's
 * configurer captures internal state from the original provider via
 * reflection on an {@code instanceof} check, so a wrapping decorator
 * silently breaks the validating-filter wiring; replacing the provider in
 * the list breaks it the same way; inserting alongside causes the SAS
 * provider to issue a code anyway (Spring's {@code ProviderManager} catches
 * the gate's {@code AuthenticationException} and falls through to the next
 * provider). A pre-endpoint filter is the clean integration point.
 */
public final class MembershipGateFilter extends OncePerRequestFilter {

    private static final RequestMatcher AUTHORIZE_MATCHER =
        PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorize");

    private final RegisteredClientRepository registeredClientRepository;
    private final ClientMembershipQuery clientMembershipQuery;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public MembershipGateFilter(
        RegisteredClientRepository registeredClientRepository,
        ClientMembershipQuery clientMembershipQuery
    ) {
        this.registeredClientRepository = registeredClientRepository;
        this.clientMembershipQuery = clientMembershipQuery;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        if (!AUTHORIZE_MATCHER.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Anonymous / unauthenticated requests fall through to the SAS chain,
        // which redirects to the tenant login. The gate only fires for an
        // authenticated end-user.
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof TenantUserDetails details)
        ) {
            chain.doFilter(request, response);
            return;
        }

        String clientIdParam = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!StringUtils.hasText(clientIdParam)) {
            chain.doFilter(request, response);
            return;
        }
        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientIdParam);
        if (registeredClient == null) {
            // Let SAS handle invalid_client — its filter has the canonical
            // error response for this case.
            chain.doFilter(request, response);
            return;
        }

        Long tenantId = TenantScope.tenantId();
        if (tenantId != null
            && clientMembershipQuery.hasMembership(details.userId(), registeredClient.getId(), tenantId)
        ) {
            chain.doFilter(request, response);
            return;
        }

        sendAccessDenied(request, response, registeredClient);
    }

    private void sendAccessDenied(
        HttpServletRequest request, HttpServletResponse response, RegisteredClient registeredClient
    ) throws IOException {
        String redirectUri = resolveRedirectUri(request, registeredClient);
        if (redirectUri == null) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "access_denied");
            return;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectUri)
            .queryParam(OAuth2ParameterNames.ERROR, OAuth2ErrorCodes.ACCESS_DENIED);
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (StringUtils.hasText(state)) {
            builder.queryParam(OAuth2ParameterNames.STATE,
                UriUtils.encode(state, StandardCharsets.UTF_8));
        }
        redirectStrategy.sendRedirect(request, response, builder.build(true).toUriString());
    }

    private static @Nullable String resolveRedirectUri(HttpServletRequest request, RegisteredClient registeredClient) {
        String requested = request.getParameter(OAuth2ParameterNames.REDIRECT_URI);
        if (StringUtils.hasText(requested) && registeredClient.getRedirectUris().contains(requested)) {
            return requested;
        }
        // The OAuth2 spec allows omitting redirect_uri when the client has
        // exactly one registered. Fall back to it; otherwise refuse to redirect
        // (sending a 403 instead of a guess is safer than picking arbitrarily).
        if (registeredClient.getRedirectUris().size() == 1) {
            return registeredClient.getRedirectUris().iterator().next();
        }
        return null;
    }
}

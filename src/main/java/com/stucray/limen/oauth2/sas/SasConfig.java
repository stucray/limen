package com.stucray.limen.oauth2.sas;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stucray.limen.auth.SasJsonMapperFactory;
import com.stucray.limen.auth.login.PostLoginIntents;
import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ClientMembershipQuery;
import com.stucray.limen.oauth2.MembershipGateFilter;
import com.stucray.limen.oauth2.TenantIssuerContextFilter;
import com.stucray.limen.oauth2.TenantLoginUrlAuthenticationEntryPoint;
import com.stucray.limen.security.SigningKeyReader;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

@Configuration
class SasConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        RegisteredClientRepository registeredClientRepository,
        ClientMembershipQuery clientMembershipQuery
    ) throws Exception {
        RequestMatcher[] endpointsMatcherHolder = new RequestMatcher[1];
        http.oauth2AuthorizationServer(authorizationServer -> {
            RequestMatcher endpointsMatcher = authorizationServer.getEndpointsMatcher();
            endpointsMatcherHolder[0] = endpointsMatcher;
            http.securityMatcher(endpointsMatcher);
            authorizationServer.oidc(oidc -> oidc
                .providerConfigurationEndpoint(pc -> pc
                    .providerConfigurationCustomizer(builder -> builder
                        .scopes(scopes -> {
                            scopes.clear();
                            scopes.addAll(OidcScopeClaims.SUPPORTED_SCOPES);
                        })
                        .claim(OidcScopeClaims.CLAIMS_SUPPORTED, OidcScopeClaims.SUPPORTED_CLAIMS)
                    )
                )
            );
        });
        // Dedicated session attribute for the SAS chain's SavedRequest so that
        // unauthenticated requests handled by other filter chains — DevTools
        // probes to /.well-known/appspecific/com.chrome.devtools.json, the
        // internal forward to /error, anything in the catch-all chain — can't
        // overwrite the /oauth2/authorize entry between save and post-login
        // read (#285). PostLoginIntents.resumeOAuth2Authorize reads from the
        // same attribute name.
        HttpSessionRequestCache sasRequestCache = new HttpSessionRequestCache();
        sasRequestCache.setSessionAttrName(PostLoginIntents.OAUTH2_AUTHORIZE_SAVED_REQUEST_ATTR);
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
            .requestCache(rc -> rc.requestCache(sasRequestCache))
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                TenantLoginUrlAuthenticationEntryPoint.fromTenantScope(),
                new OrRequestMatcher(
                    htmlRequestMatcher(),
                    PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorize")
                )
            ))
            .addFilterBefore(
                new TenantIssuerContextFilter(authorizationServerSettings()),
                CsrfFilter.class
            )
            // Translate uncaught runtime faults (e.g. JwtEncodingException from
            // signing-key unwrap) into RFC 6749 §5.2 JSON instead of letting them
            // forward to /error and surface as the catch-all chain's empty 403
            // (#293). Sits as far upstream in the chain as possible so any SAS
            // endpoint filter that throws is covered.
            .addFilterBefore(
                new SasServerErrorTranslationFilter(endpointsMatcherHolder[0]),
                org.springframework.security.web.context.SecurityContextHolderFilter.class
            )
            // Anchor the gate after AuthorizationFilter — the SAS authorization
            // endpoint filter is itself registered with addFilterAfter on the
            // same class, so the gate sits adjacent to it in the chain. By that
            // point the SecurityContext has been hydrated from session and the
            // request is allowed past `authorizeHttpRequests(...).authenticated()`,
            // so we know there is an authenticated principal to gate on.
            .addFilterAfter(
                new MembershipGateFilter(registeredClientRepository, clientMembershipQuery),
                AuthorizationFilter.class
            );
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(
        JdbcTemplate jdbcTemplate,
        TenantClientRepository tenantClientRepository
    ) {
        JdbcRegisteredClientRepository jdbcRepo = new JdbcRegisteredClientRepository(jdbcTemplate);
        return new TenantAwareRegisteredClientRepository(jdbcRepo, tenantClientRepository);
    }

    @Bean
    JsonMapper sasJsonMapper() {
        return SasJsonMapperFactory.create();
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository,
        JsonMapper sasJsonMapper
    ) {
        JdbcOAuth2AuthorizationService delegate =
            new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        delegate.setAuthorizationRowMapper(
            new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(
                registeredClientRepository, sasJsonMapper));
        delegate.setAuthorizationParametersMapper(
            new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(sasJsonMapper));
        return new TenantAwareOAuth2AuthorizationService(delegate, jdbcTemplate, registeredClientRepository, sasJsonMapper);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository
    ) {
        return new TenantAwareOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(
        TenantRepository tenantRepository,
        SigningKeyReader signingKeys
    ) {
        return new TenantJwkSource(tenantRepository, signingKeys);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
            .issuer("http://localhost:8090")
            .build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(
        ClientMembershipQuery clientMembershipQuery
    ) {
        return context -> {
            String tokenType = context.getTokenType().getValue();
            if (OAuth2TokenType.ACCESS_TOKEN.getValue().equals(tokenType)) {
                String slug = TenantScope.slug();
                if (slug != null) {
                    context.getClaims().claim("tenant", slug);
                }
                context.getClaims().claim("roles", resolveRoles(context, clientMembershipQuery));
            } else if (OidcParameterNames.ID_TOKEN.equals(tokenType)) {
                OidcScopeClaims.addClaimsForGrantedScopes(
                    context.getClaims(),
                    context.getAuthorizedScopes(),
                    context.getPrincipal() == null ? null : context.getPrincipal().getPrincipal()
                );
            }
        };
    }

    // End-user flows (authorization_code) carry a TenantUserDetails principal —
    // resolve the user's Client Membership Roles. Non-end-user flows
    // (client_credentials) have no User behind the token, so roles is empty.
    private static List<String> resolveRoles(
        JwtEncodingContext context, ClientMembershipQuery clientMembershipQuery
    ) {
        Long tenantId = TenantScope.tenantId();
        if (tenantId == null) return List.of();
        Authentication auth = context.getPrincipal();
        if (auth == null || !(auth.getPrincipal() instanceof TenantUserDetails details)) {
            return List.of();
        }
        return clientMembershipQuery.rolesFor(
            details.userId(), context.getRegisteredClient().getId(), tenantId
        );
    }

    private static MediaTypeRequestMatcher htmlRequestMatcher() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
}

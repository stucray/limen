package com.stucray.limen.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stucray.limen.auth.SasJsonMapperFactory;
import com.stucray.limen.management.clients.TenantClientRepository;
import com.stucray.limen.oauth2.TenantAwareOAuth2AuthorizationConsentService;
import com.stucray.limen.oauth2.TenantAwareOAuth2AuthorizationService;
import com.stucray.limen.oauth2.TenantAwareRegisteredClientRepository;
import com.stucray.limen.oauth2.TenantIssuerContextFilter;
import com.stucray.limen.oauth2.TenantJwkSource;
import com.stucray.limen.oauth2.TenantLoginUrlAuthenticationEntryPoint;
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
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

import java.util.Set;

@Configuration
public class SasConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
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
            );
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(
        JdbcTemplate jdbcTemplate,
        TenantClientRepository tenantClientRepository
    ) {
        JdbcRegisteredClientRepository jdbcRepo = new JdbcRegisteredClientRepository(jdbcTemplate);
        return new TenantAwareRegisteredClientRepository(jdbcRepo, tenantClientRepository);
    }

    @Bean
    public JsonMapper sasJsonMapper() {
        return SasJsonMapperFactory.create();
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
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
    public OAuth2AuthorizationConsentService authorizationConsentService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository
    ) {
        return new TenantAwareOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(
        TenantRepository tenantRepository,
        SigningKeyStore signingKeyStore
    ) {
        return new TenantJwkSource(tenantRepository, signingKeyStore);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
            .issuer("http://localhost:8090")
            .build();
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) return;
            String slug = TenantScope.slug();
            if (slug != null) {
                context.getClaims().claim("tenant", slug);
            }
            context.getClaims().claim("roles", new ArrayList<>());
        };
    }

    private static MediaTypeRequestMatcher htmlRequestMatcher() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
}

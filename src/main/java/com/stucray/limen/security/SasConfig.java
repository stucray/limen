package com.stucray.limen.security;

import com.stucray.limen.management.clients.TenantClientRepository;
import com.stucray.limen.oauth2.TenantAwareOAuth2AuthorizationService;
import com.stucray.limen.oauth2.TenantAwareRegisteredClientRepository;
import com.stucray.limen.oauth2.TenantContext;
import com.stucray.limen.oauth2.TenantIssuerContextFilter;
import com.stucray.limen.oauth2.TenantLoginUrlAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
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
                new TenantLoginUrlAuthenticationEntryPoint(),
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
    public OAuth2AuthorizationService authorizationService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository
    ) {
        JdbcOAuth2AuthorizationService delegate =
            new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        return new TenantAwareOAuth2AuthorizationService(delegate, jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public JdbcOAuth2AuthorizationConsentService authorizationConsentService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository
    ) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
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
            String slug = TenantContext.getSlug();
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

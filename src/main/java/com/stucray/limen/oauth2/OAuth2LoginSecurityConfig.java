package com.stucray.limen.oauth2;

import com.stucray.limen.auth.login.TenantLogin;
import com.stucray.limen.auth.login.TenantUrlScheme;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * SecurityFilterChain for the OAuth2 end-user login surface (/t/*&#47;login,
 * /t/*&#47;change-password). Sits between the SAS chain (HIGHEST_PRECEDENCE) and
 * the management chain (Order 2). Form-login wiring is delegated to {@link TenantLogin};
 * everything left here is distinctive to this surface (URL scope, CSRF, request cache,
 * tenant-aware entry point).
 */
@Configuration
@Order(1)
public class OAuth2LoginSecurityConfig {

    private final TenantLogin login;
    private final TenantUrlScheme oauth2UrlScheme;

    public OAuth2LoginSecurityConfig(
        TenantLogin login,
        @Qualifier("oauth2UrlScheme") TenantUrlScheme oauth2UrlScheme
    ) {
        this.login = login;
        this.oauth2UrlScheme = oauth2UrlScheme;
    }

    @Bean
    public SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http) throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

        login.applyTo(http, oauth2UrlScheme);

        http
            .securityMatcher("/t/*/login", "/t/*/change-password")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/t/*/login").permitAll()
                .anyRequest().authenticated()
            )
            .requestCache(rc -> rc.requestCache(requestCache))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                TenantLoginUrlAuthenticationEntryPoint.fromUrl()
            ))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            );

        return http.build();
    }
}

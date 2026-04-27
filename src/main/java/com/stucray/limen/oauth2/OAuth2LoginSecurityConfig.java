package com.stucray.limen.oauth2;

import com.stucray.limen.auth.OAuth2TenantAuthFilter;
import com.stucray.limen.auth.TenantPersistentTokenBasedRememberMeServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * SecurityFilterChain for the OAuth2 end-user login surface (/t/*&#47;login,
 * /t/*&#47;change-password). Sits between the SAS chain (HIGHEST_PRECEDENCE) and
 * the management chain (Order 2). Uses the shared AuthenticationManager
 * (TenantAuthProvider) so that credential validation is identical to the
 * management login.
 */
@Configuration
@Order(1)
public class OAuth2LoginSecurityConfig {

    private final AuthenticationManager authenticationManager;
    private final TenantPersistentTokenBasedRememberMeServices rememberMeServices;
    private final String rememberMeKey;

    public OAuth2LoginSecurityConfig(
        AuthenticationManager authenticationManager,
        TenantPersistentTokenBasedRememberMeServices rememberMeServices,
        @org.springframework.beans.factory.annotation.Value("${limen.security.remember-me-key}")
        String rememberMeKey
    ) {
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
        this.rememberMeKey = rememberMeKey;
    }

    @Bean
    public SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http) throws Exception {
        OAuth2TenantAuthFilter authFilter = new OAuth2TenantAuthFilter(
            authenticationManager,
            new TenantLoginSuccessHandler()
        );
        authFilter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        authFilter.setRememberMeServices(rememberMeServices);

        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

        http
            .securityMatcher("/t/*/login", "/t/*/change-password")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/t/*/login").permitAll()
                .anyRequest().authenticated()
            )
            .requestCache(rc -> rc.requestCache(requestCache))
            .addFilterAt(authFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new TenantAccessFilter(), SecurityContextHolderFilter.class)
            .rememberMe(rm -> rm.rememberMeServices(rememberMeServices).key(rememberMeKey))
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

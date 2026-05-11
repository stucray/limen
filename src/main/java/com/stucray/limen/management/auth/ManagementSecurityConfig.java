package com.stucray.limen.management.auth;

import com.stucray.limen.auth.ManagementAuthEntryPoint;
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

@Configuration
@Order(2)
class ManagementSecurityConfig {

    private final TenantLogin login;
    private final TenantUrlScheme managementUrlScheme;

    ManagementSecurityConfig(
        TenantLogin login,
        @Qualifier("managementUrlScheme") TenantUrlScheme managementUrlScheme
    ) {
        this.login = login;
        this.managementUrlScheme = managementUrlScheme;
    }

    @Bean
    SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        login.applyTo(http, managementUrlScheme);
        login.applyLogoutTo(http, managementUrlScheme);

        http
            .securityMatcher("/manage/**", "/signup")
            // /manage/system/** is for System Admins; @PreAuthorize enforces role check
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/signup").permitAll()
                .requestMatchers("/manage/t/*/login").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new ManagementAuthEntryPoint()))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            );

        return http.build();
    }
}

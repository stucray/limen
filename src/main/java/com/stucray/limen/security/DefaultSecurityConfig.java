package com.stucray.limen.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Minimal catch-all chain. Permits the public landing surfaces (`/`, `/login`,
 * `/actuator/health`) and denies everything else; tenant-scoped surfaces are
 * handled by their own higher-precedence chains (SAS, OAuth2-login, management).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Order(3)
public class DefaultSecurityConfig {

    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/actuator/health").permitAll()
                .requestMatchers("/css/**", "/images/**").permitAll()
                .anyRequest().denyAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .build();
    }
}

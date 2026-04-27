package com.stucray.limen.security;

import com.stucray.limen.oauth2.TenantLoginSuccessHandler;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Order(3)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(
        HttpSecurity http,
        PersistentTokenRepository tokenRepository,
        UserDetailsService userDetailsService,
        UserRepository userRepository,
        TenantRepository tenantRepository
    ) throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(req -> {
            if (!"GET".equalsIgnoreCase(req.getMethod())) return false;
            String accept = req.getHeader("Accept");
            return accept != null && accept.contains("text/html");
        });

        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .requestCache(rc -> rc.requestCache(requestCache))
            .formLogin(form -> form
                .loginPage("/login").permitAll()
                .successHandler(new TenantLoginSuccessHandler(userRepository, tenantRepository))
            )
            .logout(logout -> logout
                .deleteCookies("JSESSIONID", "remember-me")
                .invalidateHttpSession(true)
            )
            .rememberMe(rm -> rm
                .tokenRepository(tokenRepository)
                .tokenValiditySeconds(14 * 24 * 60 * 60)
                .userDetailsService(userDetailsService)
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .build();
    }

    @Bean
    public PersistentTokenRepository persistentTokenRepository(JdbcTemplate jdbcTemplate) {
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setJdbcTemplate(jdbcTemplate);
        return repo;
    }
}

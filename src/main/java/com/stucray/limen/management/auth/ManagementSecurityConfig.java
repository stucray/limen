package com.stucray.limen.management.auth;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@Order(2)
public class ManagementSecurityConfig {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ManagementSecurityConfig(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        TenantAuthProvider provider = new TenantAuthProvider(tenantRepository, userRepository, passwordEncoder);
        TenantAuthFilter authFilter = new TenantAuthFilter(new ProviderManager(provider));
        authFilter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());

        http
            .securityMatcher("/manage/**", "/signup")
            // /manage/system/** is for System Admins; @PreAuthorize enforces role check
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/signup").permitAll()
                .requestMatchers("/manage/t/*/login").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterAt(authFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new TenantAuthEntryPoint()))
            .logout(logout -> logout
                .logoutUrl("/manage/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    String referer = req.getHeader("Referer");
                    String redirectUrl = "/manage/t/system/login";
                    if (referer != null) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile(".*/manage/t/([^/]+)/.*").matcher(referer);
                        if (m.matches()) redirectUrl = "/manage/t/" + m.group(1) + "/login";
                    }
                    res.sendRedirect(redirectUrl);
                })
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            );

        return http.build();
    }
}

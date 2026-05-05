package com.stucray.limen.identity;

import com.stucray.limen.auth.TenantAuthProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class IdentityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
        TenantAuthProvider tenantAuthProvider,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        ProviderManager providerManager = new ProviderManager(tenantAuthProvider);
        // ProviderManager defaults to NullEventPublisher — without this hookup,
        // AuthenticationSuccessEvent / AuthenticationFailureEvent are never
        // fired, and AuditDispatcher + LoginAttemptTracker (this
        // slice) both silently never run for the login surface.
        providerManager.setAuthenticationEventPublisher(
            new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        return providerManager;
    }
}

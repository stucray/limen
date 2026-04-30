package com.stucray.limen.auth;

import com.stucray.limen.auth.login.TenantUrlScheme;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
public class AuthBeansConfig {

    @Bean
    public TenantPersistentTokenRepository tenantPersistentTokenRepository(JdbcTemplate jdbcTemplate) {
        return new TenantPersistentTokenRepository(jdbcTemplate);
    }

    @Bean
    public TenantPersistentTokenBasedRememberMeServices tenantPersistentTokenBasedRememberMeServices(
        TenantUserDetailsService tenantUserDetailsService,
        TenantPersistentTokenRepository tenantPersistentTokenRepository,
        TenantRepository tenantRepository,
        List<TenantUrlScheme> schemes,
        @Value("${limen.security.remember-me-key}") String rememberMeKey,
        @Value("${limen.security.remember-me-validity-seconds:1209600}") int validitySeconds
    ) {
        TenantPersistentTokenBasedRememberMeServices services =
            new TenantPersistentTokenBasedRememberMeServices(
                rememberMeKey, tenantUserDetailsService, tenantPersistentTokenRepository,
                tenantRepository, schemes);
        services.setTokenValiditySeconds(validitySeconds);
        return services;
    }
}

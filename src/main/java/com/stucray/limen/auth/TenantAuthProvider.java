package com.stucray.limen.auth;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class TenantAuthProvider implements AuthenticationProvider {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantAuthProvider(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        TenantAuthToken token = (TenantAuthToken) authentication;
        String slug = token.getTenantSlug();
        String email = (String) token.getPrincipal();
        String rawPassword = (String) token.getCredentials();
        if (email == null) {
            throw new BadCredentialsException("Invalid credentials");
        }

        Tenant tenant = tenantRepository.findBySlug(slug)
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (tenant.status() == TenantStatus.SUSPENDED) {
            throw new DisabledException("Tenant is suspended");
        }

        User user = userRepository.findByEmailAndTenantId(email, tenant.id())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.enabled()) {
            throw new DisabledException("User account is disabled");
        }

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        TenantUserDetails details = new TenantUserDetails(user, tenant);
        return new TenantAuthToken(slug, details, details.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return TenantAuthToken.class.isAssignableFrom(authentication);
    }
}

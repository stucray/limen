package com.stucray.limen.identity;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UserDetailsService for the OAuth2 authorization code flow login page.
 * Scoped to the system tenant until per-tenant OAuth2 routing is wired up in a later slice.
 */
@Service
public class AuthUserDetailsService implements UserDetailsService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public AuthUserDetailsService(TenantRepository tenantRepository, UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Long systemTenantId = tenantRepository.findBySlug(UserBootstrap.SYSTEM_SLUG)
            .orElseThrow(() -> new UsernameNotFoundException(username))
            .id();

        return userRepository.findByUsernameAndTenantId(username, systemTenantId)
            .map(user -> new User(
                user.username(),
                user.passwordHash(),
                user.enabled(),
                true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            ))
            .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}

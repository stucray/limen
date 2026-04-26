package com.stucray.limen.identity;

import com.stucray.limen.oauth2.TenantContext;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

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
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = tenantRepository.findBySlug(UserBootstrap.SYSTEM_SLUG)
                .orElseThrow(() -> new UsernameNotFoundException(username))
                .id();
        }
        final Long resolvedTenantId = tenantId;
        return userRepository.findByUsernameAndTenantId(username, resolvedTenantId)
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

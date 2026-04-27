package com.stucray.limen.auth;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Tenant-aware UserDetails lookup. Used by the remember-me services to
 * re-authenticate a cookie at request time; the cookie carries the tenant slug
 * as its third segment, and the matching tenant's user pool is queried.
 *
 * The single-arg {@link #loadUserByUsername(String)} method is required by the
 * {@link UserDetailsService} contract but unsupported in this codebase: every
 * authentication path already knows the tenant slug.
 */
@Service
public class TenantUserDetailsService implements UserDetailsService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public TenantUserDetailsService(TenantRepository tenantRepository, UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    public UserDetails loadByUsernameAndSlug(String username, String slug) {
        Tenant tenant = tenantRepository.findBySlug(slug)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown tenant: " + slug));
        User user = userRepository.findByUsernameAndTenantId(username, tenant.id())
            .orElseThrow(() -> new UsernameNotFoundException(username));
        return new TenantUserDetails(user, tenant);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        throw new UnsupportedOperationException(
            "Plain username lookup is not supported — use loadByUsernameAndSlug(username, slug)");
    }
}

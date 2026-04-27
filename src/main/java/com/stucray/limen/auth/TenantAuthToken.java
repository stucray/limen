package com.stucray.limen.auth;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public final class TenantAuthToken extends UsernamePasswordAuthenticationToken {

    private final String tenantSlug;

    /** Unauthenticated — used when submitting credentials. */
    public TenantAuthToken(String tenantSlug, String username, String password) {
        super(username, password);
        this.tenantSlug = tenantSlug;
    }

    /** Authenticated — returned by the AuthenticationProvider after successful verification. */
    public TenantAuthToken(String tenantSlug, TenantUserDetails principal, Collection<? extends GrantedAuthority> authorities) {
        super(principal, null, authorities);
        this.tenantSlug = tenantSlug;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }
}

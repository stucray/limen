package com.stucray.limen.user;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public final class TenantUserDetails implements UserDetails {

    private final User user;
    private final Tenant tenant;

    public TenantUserDetails(User user, Tenant tenant) {
        this.user = user;
        this.tenant = tenant;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (tenant.isSystem()) {
            return List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_TENANT_OWNER"));
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.email();
    }

    @Override
    public boolean isEnabled() {
        return user.enabled();
    }

    public String tenantSlug() { return tenant.slug(); }
    public Long tenantId()     { return tenant.id(); }
    public Long userId()       { return user.id(); }
    public String displayEmail() { return user.email(); }
    public boolean mustChangePassword() { return user.mustChangePassword(); }
    public Tenant tenant() { return tenant; }
    public User user()     { return user; }
}

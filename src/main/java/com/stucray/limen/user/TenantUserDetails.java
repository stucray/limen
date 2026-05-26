package com.stucray.limen.user;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

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

    // Value equality by (tenantId, userId) — Spring Security expects UserDetails
    // implementations to be value-equal so that SessionRegistry lookups
    // (`getAllSessions(principal, false)` at /oauth2/token, used to populate the
    // OIDC id_token `sid` claim) survive principal round-trips through
    // serialized SAS authorization rows. Without this override, the deserialized
    // principal at /oauth2/token did not match the session-registered principal
    // from /login, so no `sid` claim was added and downstream /connect/logout
    // sid validation failed. Mirrors the convention of
    // {@code org.springframework.security.core.userdetails.User}.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantUserDetails other)) return false;
        return Objects.equals(this.user.id(), other.user.id())
            && Objects.equals(this.tenant.id(), other.tenant.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(user.id(), tenant.id());
    }
}

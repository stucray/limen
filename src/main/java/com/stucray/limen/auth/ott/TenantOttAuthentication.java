package com.stucray.limen.auth.ott;

import org.springframework.security.authentication.ott.OneTimeTokenAuthentication;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serial;
import java.util.Collection;

/**
 * Tenant-aware result of a successful one-time-token authentication, carrying
 * the {@link OttIntent} of the consumed token. Lets the post-login pipeline
 * distinguish a verify-email landing from a password-reset landing without
 * fishing intent out of session state.
 *
 * <p>The intent rides on the {@code Authentication} itself — the natural
 * carrier Spring Security already persists across requests via
 * {@code HttpSessionSecurityContextRepository}. Readers (post-login intent
 * chain, password-change flow) match on {@code instanceof TenantOttAuthentication}
 * and check {@link #intent()}; the journey ends when the security context is
 * rotated to a plain authenticated token.
 */
public class TenantOttAuthentication extends OneTimeTokenAuthentication {

    @Serial private static final long serialVersionUID = 1L;

    private final OttIntent intent;

    public TenantOttAuthentication(
        Object principal,
        Collection<? extends GrantedAuthority> authorities,
        OttIntent intent
    ) {
        super(principal, authorities);
        this.intent = intent;
    }

    public OttIntent intent() {
        return intent;
    }
}

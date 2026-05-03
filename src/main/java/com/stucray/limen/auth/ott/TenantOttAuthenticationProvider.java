package com.stucray.limen.auth.ott;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.auth.TenantUserDetailsService;
import com.stucray.limen.tenant.TenantScope;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthentication;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Tenant-aware replacement for Spring's {@code OneTimeTokenAuthenticationProvider}.
 *
 * <p>The framework's provider calls {@code UserDetailsService.loadUserByUsername(token.username())},
 * which Limen's {@link TenantUserDetailsService} explicitly rejects — every
 * authentication path in this codebase already knows the tenant slug. Here, the
 * slug comes from {@link TenantScope}, bound by {@code TenantOttRoutingFilter}
 * before the security chain runs. Issuing the lookup as
 * {@code loadByEmailAndSlug(email, slug)} preserves the per-tenant user-pool
 * isolation that the rest of Limen depends on.
 *
 * <p>Side effects on consume:
 * <ul>
 *   <li>{@link OttIntent#VERIFY_EMAIL} — flips {@code email_verified=true} via
 *       {@link EmailVerificationService#markEmailVerified} (idempotent on a
 *       second consume).</li>
 *   <li>{@link OttIntent#PASSWORD_RESET} — also flips {@code email_verified=true}
 *       (clicking a link delivered to the address proves control of it, same
 *       guarantee as the verify-email flow), and sets a session breadcrumb via
 *       {@link PasswordResetSessionMarker} so {@code passwordChangeAfterReset()}
 *       can route the just-authenticated user through change-password ahead of
 *       any saved /oauth2/authorize request.</li>
 * </ul>
 */
@Component
public class TenantOttAuthenticationProvider implements AuthenticationProvider {

    private final OneTimeTokenService tokenService;
    private final TenantUserDetailsService userDetailsService;
    private final EmailVerificationService verificationService;

    public TenantOttAuthenticationProvider(
        OneTimeTokenService tokenService,
        TenantUserDetailsService userDetailsService,
        EmailVerificationService verificationService
    ) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.verificationService = verificationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OneTimeTokenAuthenticationToken otpToken = (OneTimeTokenAuthenticationToken) authentication;
        OneTimeToken consumed = tokenService.consume(otpToken);
        if (!(consumed instanceof TenantOneTimeToken row)) {
            // consume() returns null for missing / expired / cross-tenant; either
            // is an invalid-token from the client's perspective.
            throw new InvalidOneTimeTokenException("Invalid or expired one-time token");
        }

        String slug = TenantScope.slug();
        if (slug == null) {
            throw new IllegalStateException(
                "TenantOttAuthenticationProvider invoked outside TenantScope");
        }

        TenantUserDetails principal;
        try {
            principal = (TenantUserDetails) userDetailsService.loadByEmailAndSlug(row.username(), slug);
        } catch (UsernameNotFoundException ex) {
            // The token row's email does not resolve to a user in the tenant
            // (e.g. user deleted between issue and consume). Treat as bad creds
            // rather than leaking which case we hit.
            throw new BadCredentialsException("Failed to authenticate the one-time token");
        }

        // Both intents flip email_verified=true. For VERIFY_EMAIL that is the
        // whole point of the flow; for PASSWORD_RESET it is a defensible side
        // effect — the user just demonstrated control of the address by clicking
        // a link delivered to it, the same proof the verify-email flow accepts.
        // Without this an unverified user using forgot-password would be bounced
        // to /check-inbox by emailVerificationRequired() before ever reaching
        // change-password.
        verificationService.markEmailVerified(principal.userId(), principal.tenantId());
        // Reflect the just-applied flip on the in-memory principal so the
        // PostLoginIntent.emailVerificationRequired() check that runs from
        // the success handler doesn't see stale state and bounce the user
        // back to check-inbox.
        principal = new TenantUserDetails(
            principal.user().withEmailVerified(true), principal.tenant());

        if (row.intent() == OttIntent.PASSWORD_RESET) {
            // Set the session breadcrumb that passwordChangeAfterReset() reads
            // and TenantPasswordChangeFlow clears on successful submission.
            PasswordResetSessionMarker.setOnCurrentRequest();
        }

        Set<GrantedAuthority> authorities = new HashSet<>(principal.getAuthorities());
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.OTT_AUTHORITY));
        OneTimeTokenAuthentication authenticated = new OneTimeTokenAuthentication(principal, authorities);
        authenticated.setDetails(otpToken.getDetails());
        return authenticated;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OneTimeTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

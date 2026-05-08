package com.stucray.limen.auth;

import com.stucray.limen.user.TenantUserDetails;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class TenantAuthProvider implements AuthenticationProvider {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    public TenantAuthProvider(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        // System default zone matches LocalDateTime.now() used by the tracker
        // and by every test fixture; the `users.locked_until` column is a
        // bare `timestamp` (no timezone), so the producer + consumer must agree.
        this(tenantRepository, userRepository, passwordEncoder, Clock.systemDefaultZone());
    }

    /** Test seam: inject a clock so the lockout-window check is deterministic. */
    public TenantAuthProvider(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        Clock clock
    ) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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
        assertTenantActive(tenant);

        User user = userRepository.findByEmailAndTenantId(email, tenant.id())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.enabled()) {
            throw new DisabledException("User account is disabled");
        }
        assertNotLockedOut(user);

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        TenantUserDetails details = new TenantUserDetails(user, tenant);
        return new TenantAuthToken(slug, details, details.getAuthorities());
    }

    private void assertTenantActive(Tenant tenant) {
        if (tenant.status() == TenantStatus.SUSPENDED) {
            throw new DisabledException("Tenant is suspended");
        }
    }

    // Pre-auth lockout check: rejects with a distinct message before the
    // password is verified, so a locked-out user typing the right password
    // still hits the lock (PRD #120 user story 21). Counter increments are
    // suppressed for LockedException in LoginAttemptTracker, so the lock
    // window does not extend itself when the user keeps trying.
    private void assertNotLockedOut(User user) {
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(LocalDateTime.now(clock))) {
            throw new LockedException(
                "Account is locked due to too many failed attempts. "
                    + "Try again later or contact your tenant admin to unlock.");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return TenantAuthToken.class.isAssignableFrom(authentication);
    }
}

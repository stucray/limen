package com.stucray.limen.auth.lockout;

import com.stucray.limen.audit.events.AccountLockedEvent;
import com.stucray.limen.auth.TenantAuthToken;
import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Increments the per-user failed-login counter on {@code AuthenticationFailureEvent}
 * and resets it on {@code AuthenticationSuccessEvent}. When the counter crosses
 * {@link LockoutProperties#threshold()}, sets {@code users.locked_until} to
 * {@code now() + window} so the next attempt is rejected by
 * {@code TenantAuthProvider}'s pre-check.
 *
 * <p>Coexists with {@code AuditDispatcher}: Spring dispatches every event
 * to all subscribers, so the dispatcher's {@code login_failure} row and
 * this tracker's counter increment are independent. The deliberate decision
 * to keep this listener's emit (`AccountLockedEvent`) separate from the audit
 * row write means the audit dispatcher doesn't need to know what triggered a
 * lockout — it just records that an account_locked event happened.
 *
 * <p>Failure cases the tracker silently ignores (no row exists to mutate):
 * <ul>
 *   <li>Login attempt with an unknown email — no user row, nothing to track.
 *       This is also the user-existence-oracle defence: locking nonexistent
 *       emails would tell an attacker which accounts exist.</li>
 *   <li>Login attempt with the wrong tenant slug — same reason.</li>
 *   <li>The failure was the lockout itself (already-locked user typing again).
 *       Detected by checking {@code event.getException()} type so the counter
 *       does not re-increment past the lock and reset the clock.</li>
 * </ul>
 */
@Component
class LoginAttemptTracker {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final LockoutProperties lockoutProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    LoginAttemptTracker(
        UserRepository userRepository,
        TenantRepository tenantRepository,
        LockoutProperties lockoutProperties,
        ApplicationEventPublisher eventPublisher
    ) {
        // System default zone (not UTC) so the timestamps the tracker writes
        // align with the LocalDateTime values stored elsewhere in the codebase
        // (users.created_at, audit_event.occurred_at, etc.) — none of which
        // carry timezone information in the Postgres `timestamp` column type.
        this(userRepository, tenantRepository, lockoutProperties, eventPublisher, Clock.systemDefaultZone());
    }

    /** Test seam: inject a clock so lockout-window expiry is deterministic. */
    LoginAttemptTracker(
        UserRepository userRepository,
        TenantRepository tenantRepository,
        LockoutProperties lockoutProperties,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.lockoutProperties = lockoutProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        // The auth provider's pre-check throws LockedException for already-locked
        // users — incrementing again would extend the lock and prevent the user
        // from ever recovering naturally after the window expires.
        if (event.getException() instanceof LockedException) {
            return;
        }
        Authentication auth = event.getAuthentication();
        if (!(auth instanceof TenantAuthToken token)) {
            // Other auth flows (OTT, system-internal) do not contribute to
            // password-counter lockout; they have their own rate-limiting story.
            return;
        }
        Optional<User> maybeUser = lookupUser(token);
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();
        int newCount = user.failedLoginAttempts() + 1;
        if (newCount >= lockoutProperties.threshold()) {
            LocalDateTime lockedUntil = LocalDateTime.now(clock).plus(lockoutProperties.window());
            userRepository.save(user
                .withFailedLoginAttempts(newCount)
                .withLockedUntil(lockedUntil));
            eventPublisher.publishEvent(new AccountLockedEvent(
                user.tenantId(), user.id(), user.email(), lockedUntil));
        } else {
            userRepository.save(user.withFailedLoginAttempts(newCount));
        }
    }

    @EventListener
    @Transactional
    void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        if (!(auth.getPrincipal() instanceof TenantUserDetails principal)) {
            return;
        }
        User user = userRepository.findByIdAndTenantId(principal.userId(), principal.tenantId())
            .orElse(null);
        if (user == null) {
            return;
        }
        // Skip the write when nothing would change — avoids a needless UPDATE
        // and a needless audit-log row on every successful login (the common case
        // where the counter is already zero and the user was never close to
        // being locked).
        if (user.failedLoginAttempts() == 0 && user.lockedUntil() == null) {
            return;
        }
        userRepository.save(user
            .withFailedLoginAttempts(0)
            .withLockedUntil(null));
    }

    private Optional<User> lookupUser(TenantAuthToken token) {
        String slug = token.getTenantSlug();
        Object principal = token.getPrincipal();
        if (slug == null || !(principal instanceof String email) || email.isBlank()) {
            return Optional.empty();
        }
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return Optional.empty();
        }
        return userRepository.findByEmailAndTenantId(email, tenant.id());
    }
}

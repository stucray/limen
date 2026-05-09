package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.EmailVerifiedEvent;
import com.stucray.limen.audit.events.PasswordResetCompletedEvent;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named completion verbs for the OTT-driven flows. Issue and completion are
 * different operations with different shapes and different domain semantics:
 * issue is uniform across intents, but completion is intent-specific by
 * definition (verify-email flips a column; password-reset emits a journey
 * marker). Splitting them makes that asymmetry visible at every call site.
 *
 * <ul>
 *   <li>{@link #markEmailVerified(Long, Long)} — VERIFY_EMAIL completion: flip
 *       {@code users.email_verified=true} (idempotent on already-verified) and
 *       emit {@link EmailVerifiedEvent}. Called from
 *       {@code TenantOttAuthenticationProvider} on every successful OTT
 *       consume — both intents' magic links flip the bit, since clicking a
 *       link delivered to an address proves control of it.</li>
 *   <li>{@link #markPasswordResetCompleted(Long, Long)} — PASSWORD_RESET
 *       completion: emit {@link PasswordResetCompletedEvent} after the new
 *       password is set. The password rotation itself is owned by
 *       {@code TenantPasswordChangeFlow.changeAndRedirect} and fires its own
 *       {@code PasswordChangedEvent}; this is the journey-tail marker that
 *       lets audit correlate the issue → consume → completion arc.</li>
 * </ul>
 */
@Service
public class OttCompletionService {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    OttCompletionService(
        UserRepository userRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    void markEmailVerified(Long userId, Long tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "User missing for OTT consume: id=" + userId + " tenant=" + tenantId));
        if (user.emailVerified()) {
            return;
        }
        userRepository.save(user.withEmailVerified(true));
        eventPublisher.publishEvent(new EmailVerifiedEvent(tenantId, userId, user.email()));
    }

    @Transactional
    public void markPasswordResetCompleted(Long userId, Long tenantId) {
        eventPublisher.publishEvent(new PasswordResetCompletedEvent(tenantId, userId));
    }
}

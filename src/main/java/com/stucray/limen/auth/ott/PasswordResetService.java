package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.PasswordResetCompletedEvent;
import com.stucray.limen.audit.events.PasswordResetOttIssuedEvent;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Orchestrates the OTT-driven password-reset flow:
 *
 * <ul>
 *   <li>{@link #requestReset(Tenant, String)} — driven by the unauthenticated
 *       forgot-password form. Issues a {@code password-reset} OTT and dispatches
 *       the email iff the address resolves to a user in the tenant; otherwise a
 *       silent no-op with respect to email delivery so the form is not a
 *       user-existence oracle (PRD #120 user story 14). Always emits
 *       {@link PasswordResetOttIssuedEvent} so audit shows the attempt without
 *       leaking which addresses are real.</li>
 *   <li>{@link #completeReset(Long, Long)} — emits
 *       {@link PasswordResetCompletedEvent} after the user submits their new
 *       password. The hash rotation itself is owned by
 *       {@code TenantPasswordChangeFlow.changeAndRedirect}, which fires its own
 *       {@code PasswordChangedEvent}; this is the "the reset journey is done"
 *       marker that lets audit correlate the issue → consume → completion arc.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private final TenantAwareOneTimeTokenService tokenService;
    private final OttEmailNotifier emailNotifier;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PasswordResetService(
        TenantAwareOneTimeTokenService tokenService,
        OttEmailNotifier emailNotifier,
        UserRepository userRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.tokenService = tokenService;
        this.emailNotifier = emailNotifier;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void requestReset(Tenant tenant, String email) {
        Optional<User> maybeUser = userRepository.findByEmailAndTenantId(email, tenant.id());
        boolean delivered = false;
        Long userId = null;
        if (maybeUser.isPresent()) {
            User user = maybeUser.get();
            userId = user.id();
            TenantScope.run(tenant.slug(), tenant.id(), () -> {
                TenantOneTimeToken token =
                    tokenService.generateForIntent(user.email(), OttIntent.PASSWORD_RESET);
                emailNotifier.sendPasswordReset(tenant, user.email(), token.tokenValue());
            });
            delivered = true;
        }
        eventPublisher.publishEvent(new PasswordResetOttIssuedEvent(
            tenant.id(), userId, email, delivered));
    }

    @Transactional
    public void completeReset(Long userId, Long tenantId) {
        eventPublisher.publishEvent(new PasswordResetCompletedEvent(tenantId, userId));
    }
}

package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.EmailVerifiedEvent;
import com.stucray.limen.audit.events.VerificationOttIssuedEvent;
import com.stucray.limen.audit.events.VerificationResentEvent;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Orchestrates the verify-email flow:
 *
 * <ul>
 *   <li>{@link #issueVerification(Tenant, User)} — generates an OTT under the
 *       given tenant scope, dispatches the email, and publishes
 *       {@link VerificationOttIssuedEvent} for audit. Used at signup and by
 *       slice #129's system-admin tenant-create.</li>
 *   <li>{@link #resendVerification(Tenant, String)} — same effect, but driven
 *       by an unauthenticated form post; emits {@link VerificationResentEvent}
 *       in both the delivered and silently-skipped (unknown email) cases so
 *       audit shows the attempt without leaking which addresses are real.</li>
 *   <li>{@link #markEmailVerified(Long, Long)} — flips
 *       {@code users.email_verified=true} after a successful OTT consume and
 *       emits {@link EmailVerifiedEvent}. Idempotent: a second consume of the
 *       same intent is a no-op.</li>
 * </ul>
 */
@Service
public class EmailVerificationService {

    private final TenantAwareOneTimeTokenService tokenService;
    private final OttEmailNotifier emailNotifier;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EmailVerificationService(
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

    /**
     * Generate + send a verification OTT for {@code user} under {@code tenant}.
     * Binds {@link TenantScope} so callers in non-tenant-scoped paths
     * (e.g. {@code SignupService} mid-/signup, before any TenantScope filter
     * has fired) don't have to know about it.
     *
     * <p>{@code @Transactional} so the AFTER_COMMIT audit listener has a
     * transaction to hook onto when this is called outside a wrapping one.
     */
    @Transactional
    public void issueVerification(Tenant tenant, User user) {
        TenantScope.run(tenant.slug(), tenant.id(), () -> {
            TenantOneTimeToken token =
                tokenService.generateForIntent(user.email(), OttIntent.VERIFY_EMAIL);
            emailNotifier.sendVerification(tenant, user.email(), token.tokenValue());
        });
        eventPublisher.publishEvent(new VerificationOttIssuedEvent(
            tenant.id(), user.id(), user.email()));
    }

    /**
     * Resend a verification OTT for the user identified by {@code email} in
     * {@code tenant}. If no such user exists the operation is a silent no-op
     * with respect to email delivery — preserving the user-existence-oracle
     * defence — but an audit row is still recorded so investigators can see
     * the attempt.
     *
     * <p>{@code @Transactional} so the AFTER_COMMIT audit listener fires; the
     * unauthenticated controller path does not open a transaction of its own.
     */
    @Transactional
    public void resendVerification(Tenant tenant, String email) {
        Optional<User> maybeUser = userRepository.findByEmailAndTenantId(email, tenant.id());
        boolean delivered = false;
        Long userId = null;
        if (maybeUser.isPresent() && !maybeUser.get().emailVerified()) {
            User user = maybeUser.get();
            userId = user.id();
            TenantScope.run(tenant.slug(), tenant.id(), () -> {
                TenantOneTimeToken token =
                    tokenService.generateForIntent(user.email(), OttIntent.VERIFY_EMAIL);
                emailNotifier.sendVerification(tenant, user.email(), token.tokenValue());
            });
            delivered = true;
        }
        eventPublisher.publishEvent(new VerificationResentEvent(
            tenant.id(), userId, email, delivered));
    }

    /**
     * Flip {@code email_verified=true} for the user. Idempotent on the
     * already-verified case — no event is fired if the bit was already set,
     * since the consume path can in principle be called twice if the link is
     * clicked from two browser tabs in quick succession.
     */
    @Transactional
    public void markEmailVerified(Long userId, Long tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "User missing for OTT consume: id=" + userId + " tenant=" + tenantId));
        if (user.emailVerified()) {
            return;
        }
        userRepository.save(user.withEmailVerified(true));
        eventPublisher.publishEvent(new EmailVerifiedEvent(tenantId, userId, user.email()));
    }
}

package com.stucray.limen.security;

import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Orchestrates per-tenant signing-key rotation: delegates the storage swap to
 * {@link SigningKeyStore#rotateForTenant(long)} and publishes a
 * {@link SigningKeyRotatedEvent} for downstream audit + metrics.
 *
 * <p>Slice 1 ships the per-tenant {@link #rotate(long)} entry point used by
 * the storage and end-to-end tests; the scheduled batch entry point and prune
 * path arrive in slices 2 and 3 respectively (PRD #173). The {@link Clock}
 * field is unused today but injected now to lock in the constructor seam used
 * by the slice-3 batch tests, mirroring {@code TenantAwareOneTimeTokenService}.
 */
@Component
public class SigningKeyRotator {

    private final SigningKeyStore signingKeyStore;
    private final ApplicationEventPublisher eventPublisher;
    @SuppressWarnings("unused")
    private final Clock clock;

    @Autowired
    public SigningKeyRotator(SigningKeyStore signingKeyStore, ApplicationEventPublisher eventPublisher) {
        this(signingKeyStore, eventPublisher, Clock.systemUTC());
    }

    /** Test seam: inject a clock to drive scheduled-rotation timing deterministically. */
    public SigningKeyRotator(
        SigningKeyStore signingKeyStore,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.signingKeyStore = signingKeyStore;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void rotate(long tenantId) {
        SigningKeyStore.RotationOutcome outcome = signingKeyStore.rotateForTenant(tenantId);
        eventPublisher.publishEvent(new SigningKeyRotatedEvent(
            tenantId, outcome.oldKid(), outcome.newKid()));
    }
}

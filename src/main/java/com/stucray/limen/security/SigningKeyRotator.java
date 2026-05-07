package com.stucray.limen.security;

import com.stucray.limen.audit.events.SigningKeyPrunedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Orchestrates per-tenant signing-key rotation: delegates storage writes to
 * {@link SigningKeyStore} and publishes one domain event per affected key for
 * downstream audit + metrics.
 *
 * <p>Slice 1 shipped the per-tenant {@link #rotate(long)} entry point; slice 2
 * adds {@link #pruneRetired(Duration)} for grace-expired RETIRED rows. The
 * scheduled batch driver that ties both together arrives in slice 3 (PRD #173).
 * The {@link Clock} field is unused today but injected now to lock in the
 * constructor seam used by the slice-3 batch tests, mirroring
 * {@code TenantAwareOneTimeTokenService}.
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

    /**
     * Reaps every {@code RETIRED} signing key whose {@code retired_at} is older
     * than {@code grace}. One {@link SigningKeyPrunedEvent} fires per deleted
     * row so each pruned key lands its own audit row + counter increment;
     * AFTER_COMMIT semantics on the listeners require this method's
     * transaction to reach commit, so the events fire as a single batch when
     * this method returns successfully.
     */
    @Transactional
    public void pruneRetired(Duration grace) {
        for (SigningKeyStore.PrunedKey pruned : signingKeyStore.pruneRetiredOlderThan(grace)) {
            eventPublisher.publishEvent(new SigningKeyPrunedEvent(pruned.tenantId(), pruned.kid()));
        }
    }
}

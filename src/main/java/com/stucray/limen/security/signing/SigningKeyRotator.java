package com.stucray.limen.security.signing;

import com.stucray.limen.audit.events.SigningKeyPrunedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import com.stucray.limen.audit.events.SigningKeyRotationFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Orchestrates per-tenant signing-key rotation. Three entry points:
 *
 * <ul>
 *   <li>{@link #rotate(long)} — rotates one tenant in its own transaction;
 *       publishes a {@link SigningKeyRotatedEvent} on commit.</li>
 *   <li>{@link #pruneRetired(Duration)} — reaps grace-expired RETIRED rows in
 *       one transaction; publishes one {@link SigningKeyPrunedEvent} per row
 *       on commit.</li>
 *   <li>{@link #runScheduledRotation()} — slice-3 batch driver. Iterates
 *       eligible tenants, calls {@link #rotate(long)} per tenant via the
 *       Spring-injected self-proxy so each rotation gets its own transaction
 *       (failure isolation), then prunes once at end. Per-tenant failures land
 *       a {@link SigningKeyRotationFailedEvent} and processing continues.</li>
 * </ul>
 *
 * <p>The {@link Clock} field is reserved for future scheduled-rotation timing
 * assertions; today it is only held to lock in the constructor seam used by
 * existing tests, mirroring {@code TenantAwareOneTimeTokenService}.
 *
 * <p>Why {@code @Lazy SigningKeyRotator self}: Spring's {@code @Transactional}
 * is AOP-proxy-driven, so calling {@code this.rotate(...)} from inside this
 * class bypasses the proxy and the transaction never opens. The lazy
 * self-injection gives {@link #runScheduledRotation()} a handle to the proxy
 * so per-tenant rotations actually commit independently — which is what makes
 * AFTER_COMMIT audit + counter listeners fire per tenant.
 */
@Component
class SigningKeyRotator {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyRotator.class);

    private final SigningKeyLifecycle signingKeys;
    private final ApplicationEventPublisher eventPublisher;
    private final SigningKeyRotationProperties properties;
    private final SigningKeyRotator self;
    @SuppressWarnings("unused")
    private final Clock clock;

    @Autowired
    SigningKeyRotator(
        SigningKeyLifecycle signingKeys,
        ApplicationEventPublisher eventPublisher,
        SigningKeyRotationProperties properties,
        @Lazy SigningKeyRotator self
    ) {
        this(signingKeys, eventPublisher, properties, self, Clock.systemUTC());
    }

    /** Test seam — accepts a stubbed self-proxy for batch-path tests; clock for timing-sensitive ones. */
    SigningKeyRotator(
        SigningKeyLifecycle signingKeys,
        ApplicationEventPublisher eventPublisher,
        SigningKeyRotationProperties properties,
        SigningKeyRotator self,
        Clock clock
    ) {
        this.signingKeys = signingKeys;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.self = self;
        this.clock = clock;
    }

    @Transactional
    void rotate(long tenantId) {
        SigningKeyLifecycle.RotationOutcome outcome = signingKeys.rotateForTenant(tenantId);
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
    void pruneRetired(Duration grace) {
        for (SigningKeyLifecycle.PrunedKey pruned : signingKeys.pruneRetiredOlderThan(grace)) {
            eventPublisher.publishEvent(new SigningKeyPrunedEvent(pruned.tenantId(), pruned.kid()));
        }
    }

    /**
     * Daily batch entry point invoked by {@link SigningKeyRotationSchedule}.
     * Each per-tenant rotation runs in its own transaction (via the Spring
     * self-proxy) so a failure in one tenant doesn't roll back the rotations
     * already committed for earlier tenants. Per-tenant exceptions are caught,
     * logged WARN, and trigger a {@link SigningKeyRotationFailedEvent} with
     * the exception's simple class name as {@code cause} — processing
     * continues for subsequent tenants. Pruning runs once at the end of the
     * batch regardless of per-tenant outcomes.
     */
    void runScheduledRotation() {
        List<Long> tenantIds = signingKeys.findTenantIdsWithActiveKeyOlderThan(properties.keyAge());
        log.info("Scheduled signing-key rotation: {} tenants eligible", tenantIds.size());

        for (long tenantId : tenantIds) {
            try {
                self.rotate(tenantId);
            } catch (RuntimeException e) {
                log.warn("Signing-key rotation failed for tenant {}: {}", tenantId, e.toString());
                eventPublisher.publishEvent(new SigningKeyRotationFailedEvent(
                    tenantId, e.getClass().getSimpleName()));
            }
        }

        self.pruneRetired(properties.gracePeriod());
    }
}

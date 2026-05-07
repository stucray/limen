package com.stucray.limen.observability;

import com.stucray.limen.audit.events.ClientSecretRotatedEvent;
import com.stucray.limen.audit.events.SigningKeyPrunedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import com.stucray.limen.audit.events.SigningKeyRotationFailedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Named Micrometer counters for security-relevant audit events. Exported via
 * the OpenTelemetry starter to OTLP/HTTP, so they show up in Mimir alongside
 * the auto-instrumented {@code http.server.requests} et al.
 *
 * <p>Why this exists separately from {@code AuditDispatcher}: the dispatcher
 * persists durable audit rows; metrics are best-effort tallies for
 * dashboards/alerting. Counter increments don't need {@code event_publication}
 * persistence — a JVM crash that misses an increment is acceptable, whereas a
 * missed audit row is not. So this class uses
 * {@link TransactionalEventListener} (AFTER_COMMIT timing without registry
 * persistence) instead of {@code @ApplicationModuleListener}.
 *
 * <p>Cardinality: no tenant tag (would balloon Mimir series with each
 * onboarded tenant). The {@code cause} tag on {@code limen.auth.login.failure}
 * is bounded by Spring Security's exception classes (BadCredentials, Locked,
 * Disabled, …) — a small, fixed set.
 */
@Component
public class AuditMetricsListener {

    static final String LOGIN_SUCCESS = "limen.auth.login.success";
    static final String LOGIN_FAILURE = "limen.auth.login.failure";
    static final String CLIENT_SECRET_ROTATED = "limen.oauth2.client.secret.rotated";
    static final String SIGNING_KEY_ROTATED = "limen.security.signing_key.rotated";
    static final String SIGNING_KEY_PRUNED = "limen.security.signing_key.pruned";
    static final String SIGNING_KEY_ROTATION_FAILURE = "limen.security.signing_key.rotation.failure";

    private final MeterRegistry registry;
    private final Counter loginSuccess;
    private final Counter clientSecretRotated;
    private final Counter signingKeyRotated;
    private final Counter signingKeyPruned;

    public AuditMetricsListener(MeterRegistry registry) {
        this.registry = registry;
        this.loginSuccess = Counter.builder(LOGIN_SUCCESS)
            .description("Successful logins (Spring Security AuthenticationSuccessEvent).")
            .baseUnit("events")
            .register(registry);
        this.clientSecretRotated = Counter.builder(CLIENT_SECRET_ROTATED)
            .description("OAuth2 client secret rotations.")
            .baseUnit("events")
            .register(registry);
        this.signingKeyRotated = Counter.builder(SIGNING_KEY_ROTATED)
            .description("Per-tenant JWT signing-key rotations.")
            .baseUnit("events")
            .register(registry);
        this.signingKeyPruned = Counter.builder(SIGNING_KEY_PRUNED)
            .description("Per-tenant JWT signing keys pruned after the grace window.")
            .baseUnit("events")
            .register(registry);
    }

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        loginSuccess.increment();
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        Counter.builder(LOGIN_FAILURE)
            .description("Failed logins, tagged by Spring Security exception class.")
            .baseUnit("events")
            .tag("cause", event.getException().getClass().getSimpleName())
            .register(registry)
            .increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClientSecretRotated(ClientSecretRotatedEvent event) {
        clientSecretRotated.increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSigningKeyRotated(SigningKeyRotatedEvent event) {
        signingKeyRotated.increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSigningKeyPruned(SigningKeyPrunedEvent event) {
        signingKeyPruned.increment();
    }

    /**
     * Fired by {@code SigningKeyRotator.runScheduledRotation()} when a
     * per-tenant rotation throws mid-batch. Synchronous {@code @EventListener}
     * (not transactional): the failed transaction has already rolled back by
     * the time this fires, so there's no commit phase to bind to and no
     * counter increment we want to suppress on rollback.
     *
     * <p>{@code cause} cardinality is bounded by the set of exception classes
     * the rotation path can throw (DB-driver, ShedLock, IllegalState, etc.) —
     * a small fixed set, like {@code limen.auth.login.failure}.
     */
    @EventListener
    public void onSigningKeyRotationFailure(SigningKeyRotationFailedEvent event) {
        Counter.builder(SIGNING_KEY_ROTATION_FAILURE)
            .description("Per-tenant signing-key rotations that threw mid-batch, tagged by exception class.")
            .baseUnit("events")
            .tag("cause", event.cause())
            .register(registry)
            .increment();
    }
}

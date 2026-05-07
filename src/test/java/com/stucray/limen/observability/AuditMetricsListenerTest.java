package com.stucray.limen.observability;

import com.stucray.limen.audit.events.ClientSecretRotatedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests on the listener's counter wiring. Spring's listener
 * dispatch is exercised by the broader observability integration tests; here
 * we cover the per-method behaviour: increments, tag values, and that the
 * static-name counters pre-register at construction so they show up in
 * {@code /actuator/metrics} before any traffic hits the app.
 */
@DisplayName("AuditMetricsListener counter wiring")
class AuditMetricsListenerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditMetricsListener listener = new AuditMetricsListener(meters);

    @Test
    @DisplayName("Static-name counters are registered eagerly at construction")
    void countersPreRegisteredAtConstruction() {
        assertThat(meters.find(AuditMetricsListener.LOGIN_SUCCESS).counter()).isNotNull();
        assertThat(meters.find(AuditMetricsListener.CLIENT_SECRET_ROTATED).counter()).isNotNull();
        assertThat(meters.find(AuditMetricsListener.SIGNING_KEY_ROTATED).counter()).isNotNull();
    }

    @Test
    @DisplayName("AuthenticationSuccessEvent increments login.success")
    void onLoginSuccessIncrements() {
        listener.onLoginSuccess(new AuthenticationSuccessEvent(authToken()));

        assertThat(meters.counter(AuditMetricsListener.LOGIN_SUCCESS).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AbstractAuthenticationFailureEvent increments login.failure with cause tag set to the exception simple name")
    void onLoginFailureIncrementsWithCauseTag() {
        listener.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
            authToken(), new BadCredentialsException("bad")));

        Counter c = meters.find(AuditMetricsListener.LOGIN_FAILURE)
            .tag("cause", "BadCredentialsException")
            .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Different exception classes produce distinct series under the same metric name")
    void differentCausesGetDistinctSeries() {
        listener.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
            authToken(), new BadCredentialsException("bad")));
        listener.onLoginFailure(new AuthenticationFailureLockedEvent(
            authToken(), new LockedException("locked")));
        listener.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
            authToken(), new BadCredentialsException("bad again")));

        assertThat(meters.find(AuditMetricsListener.LOGIN_FAILURE)
            .tag("cause", "BadCredentialsException")
            .counter()
            .count()).isEqualTo(2.0);
        assertThat(meters.find(AuditMetricsListener.LOGIN_FAILURE)
            .tag("cause", "LockedException")
            .counter()
            .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ClientSecretRotatedEvent increments oauth2.client.secret.rotated")
    void onClientSecretRotatedIncrements() {
        listener.onClientSecretRotated(new ClientSecretRotatedEvent(42L, "client-id", 7L));

        assertThat(meters.counter(AuditMetricsListener.CLIENT_SECRET_ROTATED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SigningKeyRotatedEvent increments security.signing_key.rotated")
    void onSigningKeyRotatedIncrements() {
        listener.onSigningKeyRotated(new SigningKeyRotatedEvent(42L, "old-kid", "new-kid"));

        assertThat(meters.counter(AuditMetricsListener.SIGNING_KEY_ROTATED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Counters carry no tenant tag (cardinality safety)")
    void countersHaveNoTenantTag() {
        listener.onLoginSuccess(new AuthenticationSuccessEvent(authToken()));
        listener.onClientSecretRotated(new ClientSecretRotatedEvent(42L, "client-id", 7L));
        listener.onSigningKeyRotated(new SigningKeyRotatedEvent(42L, "old-kid", "new-kid"));

        assertThat(meters.find(AuditMetricsListener.LOGIN_SUCCESS).counter().getId().getTags())
            .isEmpty();
        assertThat(meters.find(AuditMetricsListener.CLIENT_SECRET_ROTATED).counter().getId().getTags())
            .isEmpty();
        assertThat(meters.find(AuditMetricsListener.SIGNING_KEY_ROTATED).counter().getId().getTags())
            .isEmpty();
    }

    private static Authentication authToken() {
        return new UsernamePasswordAuthenticationToken("alice@example.com", "pw");
    }
}

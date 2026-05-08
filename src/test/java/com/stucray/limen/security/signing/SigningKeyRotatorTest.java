package com.stucray.limen.security.signing;

import com.stucray.limen.security.SigningKeyStore;
import com.stucray.limen.audit.events.SigningKeyPrunedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
import com.stucray.limen.audit.events.SigningKeyRotationFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("SigningKeyRotator: per-tenant rotation orchestration + event publication")
class SigningKeyRotatorTest {

    @Mock SigningKeyStore signingKeyStore;
    @Mock ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("rotate() delegates to the store and publishes a SigningKeyRotatedEvent carrying the returned kids")
    void rotateDelegatesAndPublishesEvent() {
        given(signingKeyStore.rotateForTenant(42L))
            .willReturn(new SigningKeyStore.RotationOutcome("old-kid", "new-kid"));
        SigningKeyRotator rotator = rotator(null);

        rotator.rotate(42L);

        verify(signingKeyStore).rotateForTenant(42L);
        ArgumentCaptor<SigningKeyRotatedEvent> captor = ArgumentCaptor.forClass(SigningKeyRotatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SigningKeyRotatedEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(42L);
        assertThat(event.oldKid()).isEqualTo("old-kid");
        assertThat(event.newKid()).isEqualTo("new-kid");
    }

    @Test
    @DisplayName("rotate() propagates the store's IllegalStateException without publishing an event when there is no ACTIVE key to rotate")
    void rotatePropagatesStoreFailureAndDoesNotPublish() {
        willThrow(new IllegalStateException("no ACTIVE key"))
            .given(signingKeyStore).rotateForTenant(42L);
        SigningKeyRotator rotator = rotator(null);

        assertThatThrownBy(() -> rotator.rotate(42L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ACTIVE key");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("pruneRetired() publishes one SigningKeyPrunedEvent per row the store deleted, carrying tenantId + kid from the store outcome")
    void prunePublishesOneEventPerDeletedRow() {
        given(signingKeyStore.pruneRetiredOlderThan(Duration.ofHours(24))).willReturn(List.of(
            new SigningKeyStore.PrunedKey(7L, "kid-a"),
            new SigningKeyStore.PrunedKey(7L, "kid-b"),
            new SigningKeyStore.PrunedKey(11L, "kid-c")
        ));
        SigningKeyRotator rotator = rotator(null);

        rotator.pruneRetired(Duration.ofHours(24));

        ArgumentCaptor<SigningKeyPrunedEvent> captor = ArgumentCaptor.forClass(SigningKeyPrunedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(SigningKeyPrunedEvent::tenantId, SigningKeyPrunedEvent::kid)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(7L, "kid-a"),
                org.assertj.core.groups.Tuple.tuple(7L, "kid-b"),
                org.assertj.core.groups.Tuple.tuple(11L, "kid-c"));
    }

    @Test
    @DisplayName("pruneRetired() publishes nothing when the store reports zero deletions")
    void prunePublishesNothingWhenNothingDeleted() {
        given(signingKeyStore.pruneRetiredOlderThan(Duration.ofHours(24))).willReturn(List.of());
        SigningKeyRotator rotator = rotator(null);

        rotator.pruneRetired(Duration.ofHours(24));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("runScheduledRotation() rotates each eligible tenant via the self-proxy and prunes once at the end")
    void runScheduledRotationIteratesAllEligibleTenantsThenPrunes() {
        SigningKeyRotator self = lenientMock();
        given(signingKeyStore.findTenantIdsWithActiveKeyOlderThan(Duration.ofDays(30)))
            .willReturn(List.of(7L, 11L, 13L));
        SigningKeyRotator rotator = rotator(self);

        rotator.runScheduledRotation();

        var inOrder = inOrder(self);
        inOrder.verify(self).rotate(7L);
        inOrder.verify(self).rotate(11L);
        inOrder.verify(self).rotate(13L);
        inOrder.verify(self).pruneRetired(Duration.ofHours(24));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("runScheduledRotation() catches a per-tenant exception, publishes a SigningKeyRotationFailedEvent with cause = exception simple name, and continues with later tenants and the prune")
    void runScheduledRotationContinuesPastFailureAndIncrementsFailureCounter() {
        SigningKeyRotator self = lenientMock();
        given(signingKeyStore.findTenantIdsWithActiveKeyOlderThan(Duration.ofDays(30)))
            .willReturn(List.of(7L, 11L, 13L));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
            .when(self).rotate(11L);
        SigningKeyRotator rotator = rotator(self);

        rotator.runScheduledRotation();

        verify(self).rotate(7L);
        verify(self).rotate(11L);
        verify(self).rotate(13L);
        verify(self).pruneRetired(Duration.ofHours(24));

        ArgumentCaptor<SigningKeyRotationFailedEvent> captor =
            ArgumentCaptor.forClass(SigningKeyRotationFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SigningKeyRotationFailedEvent failure = captor.getValue();
        assertThat(failure.tenantId()).isEqualTo(11L);
        assertThat(failure.cause()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("runScheduledRotation() with no eligible tenants still calls prune (RETIRED keys may still need reaping)")
    void runScheduledRotationStillPrunesWhenNoTenantsEligible() {
        SigningKeyRotator self = lenientMock();
        given(signingKeyStore.findTenantIdsWithActiveKeyOlderThan(Duration.ofDays(30)))
            .willReturn(List.of());
        SigningKeyRotator rotator = rotator(self);

        rotator.runScheduledRotation();

        org.mockito.Mockito.verify(self, org.mockito.Mockito.never()).rotate(org.mockito.ArgumentMatchers.anyLong());
        verify(self).pruneRetired(Duration.ofHours(24));
        verifyNoInteractions(eventPublisher);
    }

    private SigningKeyRotator rotator(SigningKeyRotator self) {
        SigningKeyRotationProperties props = new SigningKeyRotationProperties(
            true, "0 0 3 * * *", Duration.ofDays(30), Duration.ofHours(24));
        // self can be null for tests that don't exercise the batch path.
        SigningKeyRotator nonNullSelf = self != null ? self : lenientMock();
        return new SigningKeyRotator(signingKeyStore, eventPublisher, props, nonNullSelf, clock);
    }

    /**
     * Lenient SigningKeyRotator mock: Mockito's strict stubbing throws
     * {@code PotentialStubbingProblem} on unstubbed args of an otherwise-stubbed
     * method, which the rotator's catch block would mistakenly treat as a
     * rotation failure. The lenient mode preserves the "do nothing on
     * unstubbed call" default we rely on here.
     */
    private static SigningKeyRotator lenientMock() {
        return org.mockito.Mockito.mock(SigningKeyRotator.class,
            org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
    }
}

package com.stucray.limen.security;

import com.stucray.limen.audit.events.SigningKeyPrunedEvent;
import com.stucray.limen.audit.events.SigningKeyRotatedEvent;
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
        SigningKeyRotator rotator = new SigningKeyRotator(signingKeyStore, eventPublisher, clock);

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
        SigningKeyRotator rotator = new SigningKeyRotator(signingKeyStore, eventPublisher, clock);

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
        SigningKeyRotator rotator = new SigningKeyRotator(signingKeyStore, eventPublisher, clock);

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
        SigningKeyRotator rotator = new SigningKeyRotator(signingKeyStore, eventPublisher, clock);

        rotator.pruneRetired(Duration.ofHours(24));

        verifyNoInteractions(eventPublisher);
    }
}

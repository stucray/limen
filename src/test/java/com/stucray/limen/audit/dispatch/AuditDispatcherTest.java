package com.stucray.limen.audit.dispatch;

import com.stucray.limen.audit.AuditEvent;
import com.stucray.limen.audit.AuditEventWriter;
import com.stucray.limen.audit.events.RateLimitHitEvent;
import com.stucray.limen.audit.events.TenantCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pure unit tests on the dispatcher's behaviour. Spring's listener wiring is
 * exercised by {@link com.stucray.limen.audit.AuditEventEmitIntegrationTest};
 * here we cover the behaviours that are awkward to trigger end-to-end:
 * writer-failure swallowing, null-projection short-circuit, and the
 * "no rule matches → no write" path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditDispatcher dispatch rules")
class AuditDispatcherTest {

    @Mock AuditEventWriter writer;

    private final AuditRegistry registry = new AuditRegistry();

    @Test
    @DisplayName("AFTER_COMMIT path: matching event writes a row with rule-derived event_type")
    void afterCommitWritesRow() {
        AuditDispatcher dispatcher = new AuditDispatcher(writer, registry);
        TenantCreatedEvent event = new TenantCreatedEvent(42L, "acme", "Acme Inc", 7L);

        dispatcher.onAfterCommit(event);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("tenant_created");
        assertThat(captor.getValue().tenantId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("IMMEDIATE path: matching event writes a row with the projection's ipOverride")
    void immediateWritesRow() {
        AuditDispatcher dispatcher = new AuditDispatcher(writer, registry);
        RateLimitHitEvent event = new RateLimitHitEvent("rule-1", "k", "/x", "GET", "8.8.8.8", 30);

        dispatcher.onImmediate(event);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().ipAddress()).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("Writer that throws is swallowed — exception does not propagate to the publisher")
    void writerExceptionSwallowed() {
        doThrow(new RuntimeException("db down")).when(writer).write(any());
        AuditDispatcher dispatcher = new AuditDispatcher(writer, registry);
        TenantCreatedEvent event = new TenantCreatedEvent(42L, "acme", "Acme Inc", 7L);

        // Must not throw — the publishing transaction has already committed,
        // and an audit failure cannot be allowed to break upstream callers
        // (e.g. the rate-limit filter publishing via @EventListener).
        dispatcher.onAfterCommit(event);
        dispatcher.onImmediate(new RateLimitHitEvent("r", null, "/x", "GET", "1.1.1.1", 1));
    }

    @Test
    @DisplayName("Null projection (login_success with non-tenant principal) does not write a row")
    void nullProjectionShortCircuit() {
        AuditDispatcher dispatcher = new AuditDispatcher(writer, registry);
        Authentication auth = new UsernamePasswordAuthenticationToken("not-a-tenant-user", "pw");
        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(auth);

        dispatcher.onImmediate(event);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("Event with no matching rule is silently ignored")
    void noMatchingRuleSilentlyIgnored() {
        AuditDispatcher dispatcher = new AuditDispatcher(writer, registry);

        dispatcher.onImmediate("some random event");
        dispatcher.onAfterCommit(123);

        verify(writer, never()).write(any());
    }
}

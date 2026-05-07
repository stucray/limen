package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.AuditedDomainEvent;
import com.stucray.limen.audit.events.VerificationOttIssuedEvent;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VerifyEmailHandler describes the VERIFY_EMAIL intent")
class VerifyEmailHandlerTest {

    private final OttIntentHandler handler = new VerifyEmailHandler();
    private final Tenant tenant = new Tenant(
        7L, "acme", "Acme Inc", TenantStatus.ACTIVE, LocalDateTime.now());

    @Test
    @DisplayName("intent() returns VERIFY_EMAIL")
    void intentIsVerifyEmail() {
        assertThat(handler.intent()).isEqualTo(OttIntent.VERIFY_EMAIL);
    }

    @Test
    @DisplayName("subject is fixed verification copy regardless of tenant name")
    void subjectIsVerificationCopy() {
        assertThat(handler.subject(tenant)).isEqualTo("Verify your Limen email address");
    }

    @Test
    @DisplayName("body embeds the tenant displayName and the magic link verbatim")
    void bodyEmbedsTenantNameAndLink() {
        String body = handler.body(tenant, "/t/acme/login/ott?token=abc");

        assertThat(body)
            .contains("Welcome to Acme Inc on Limen.")
            .contains("/t/acme/login/ott?token=abc")
            .contains("60 minutes");
    }

    @Test
    @DisplayName("issuedEvent factory builds a VerificationOttIssuedEvent with the canonical (tenantId, userId, email, delivered) shape")
    void issuedEventShape() {
        AuditedDomainEvent delivered =
            handler.issuedEvent(tenant, 9L, "u@x.test", true);
        AuditedDomainEvent oracleDefended =
            handler.issuedEvent(tenant, null, "ghost@x.test", false);

        assertThat(delivered).isEqualTo(new VerificationOttIssuedEvent(7L, 9L, "u@x.test", true));
        assertThat(oracleDefended).isEqualTo(new VerificationOttIssuedEvent(7L, null, "ghost@x.test", false));
    }
}

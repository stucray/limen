package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.AuditedDomainEvent;
import com.stucray.limen.audit.events.PasswordResetOttIssuedEvent;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PasswordResetHandler describes the PASSWORD_RESET intent")
class PasswordResetHandlerTest {

    private final OttIntentHandler handler = new PasswordResetHandler();
    private final Tenant tenant = new Tenant(
        7L, "acme", "Acme Inc", TenantStatus.ACTIVE, LocalDateTime.now());

    @Test
    @DisplayName("intent() returns PASSWORD_RESET")
    void intentIsPasswordReset() {
        assertThat(handler.intent()).isEqualTo(OttIntent.PASSWORD_RESET);
    }

    @Test
    @DisplayName("subject is fixed reset copy regardless of tenant name")
    void subjectIsResetCopy() {
        assertThat(handler.subject(tenant)).isEqualTo("Reset your Limen password");
    }

    @Test
    @DisplayName("body embeds the tenant displayName, the magic link, and the safe-to-ignore line")
    void bodyEmbedsTenantNameLinkAndSafeToIgnore() {
        String body = handler.body(tenant, "/t/acme/login/ott?token=abc");

        assertThat(body)
            .contains("password-reset request")
            .contains("Acme Inc")
            .contains("/t/acme/login/ott?token=abc")
            .contains("safely ignore");
    }

    @Test
    @DisplayName("issuedEvent factory builds a PasswordResetOttIssuedEvent with the canonical (tenantId, userId, email, delivered) shape")
    void issuedEventShape() {
        AuditedDomainEvent delivered =
            handler.issuedEvent(tenant, 9L, "u@x.test", true);
        AuditedDomainEvent oracleDefended =
            handler.issuedEvent(tenant, null, "ghost@x.test", false);

        assertThat(delivered).isEqualTo(new PasswordResetOttIssuedEvent(7L, 9L, "u@x.test", true));
        assertThat(oracleDefended).isEqualTo(new PasswordResetOttIssuedEvent(7L, null, "ghost@x.test", false));
    }
}

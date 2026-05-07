package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.AuditedDomainEvent;
import com.stucray.limen.audit.events.PasswordResetOttIssuedEvent;
import com.stucray.limen.tenant.Tenant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class PasswordResetHandler implements OttIntentHandler {

    @Override
    public OttIntent intent() {
        return OttIntent.PASSWORD_RESET;
    }

    @Override
    public String subject(Tenant tenant) {
        return "Reset your Limen password";
    }

    @Override
    public String body(Tenant tenant, String magicLink) {
        return ""
            + "We received a password-reset request for your account at "
            + tenant.displayName() + ".\n\n"
            + "Click the link below to set a new password:\n\n"
            + magicLink + "\n\n"
            + "This link is single-use and will expire in 60 minutes.\n"
            + "If you didn't request this, you can safely ignore the message.\n";
    }

    @Override
    public AuditedDomainEvent issuedEvent(
        Tenant tenant, @Nullable Long userId, String email, boolean delivered
    ) {
        return new PasswordResetOttIssuedEvent(tenant.id(), userId, email, delivered);
    }
}

package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.AuditedDomainEvent;
import com.stucray.limen.audit.events.VerificationOttIssuedEvent;
import com.stucray.limen.tenant.Tenant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class VerifyEmailHandler implements OttIntentHandler {

    @Override
    public OttIntent intent() {
        return OttIntent.VERIFY_EMAIL;
    }

    @Override
    public String subject(Tenant tenant) {
        return "Verify your Limen email address";
    }

    @Override
    public String body(Tenant tenant, String magicLink) {
        return "Welcome to " + tenant.displayName() + " on Limen.\n\n"
            + "Click the link below to verify your email address and activate your account:\n\n"
            + magicLink + "\n\n"
            + "This link is single-use and will expire in 60 minutes.\n";
    }

    @Override
    public AuditedDomainEvent issuedEvent(
        Tenant tenant, @Nullable Long userId, String email, boolean delivered
    ) {
        return new VerificationOttIssuedEvent(tenant.id(), userId, email, delivered);
    }
}

package com.stucray.limen.auth.ott;

import org.springframework.security.authentication.ott.OneTimeToken;

import java.time.Instant;

/**
 * The Spring framework's {@link OneTimeToken} carries only token + username +
 * expiresAt. {@code TenantAwareOneTimeTokenService} returns this richer record
 * so callers downstream of {@code consume()} (the OTT auth provider, the
 * post-OTT dispatch logic) can read the issuing tenant and the original intent
 * without a second SELECT.
 */
public record TenantOneTimeToken(
    String tokenValue,
    String username,
    Instant expiresAt,
    Long tenantId,
    OttIntent intent
) implements OneTimeToken {

    @Override
    public String getTokenValue() {
        return tokenValue;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Instant getExpiresAt() {
        return expiresAt;
    }
}

package com.stucray.limen.auth;

import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;

import java.util.Date;

/**
 * Extends Spring's {@link PersistentRememberMeToken} with the tenant id read
 * from {@code persistent_logins.tenant_id}. Returned by
 * {@link TenantPersistentTokenRepository#getTokenForSeries(String, Long)} so
 * the caller can verify that the cookie's claimed tenant matches the row's.
 */
final class TenantPersistentRememberMeToken extends PersistentRememberMeToken {

    private final Long tenantId;

    public TenantPersistentRememberMeToken(
        String username, String series, String tokenValue, Date date, Long tenantId
    ) {
        super(username, series, tokenValue, date);
        this.tenantId = tenantId;
    }

    public Long getTenantId() {
        return tenantId;
    }
}

package com.stucray.limen.clients;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Controller binding target for the edit-client form. Spring's data binder
 * populates this via setters; missing form fields keep the field-initialised
 * defaults below, which preserves the prior {@code @RequestParam(defaultValue = …)}
 * behaviour for the token-settings fields exactly.
 *
 * <p>Strings (redirect URIs, post-logout redirect URIs, scopes) are kept as raw
 * separator-delimited input here; parsing into typed collections happens at
 * the controller before building an {@link UpdateClientCommand}.
 *
 * <p>Confidentiality is intentionally absent — flipping it requires
 * generating/discarding a client secret and changing the authentication
 * method, which warrants its own flow.
 */
final class UpdateClientForm {

    private List<String> grantTypes = List.of();
    private @Nullable String redirectUris;
    private @Nullable String postLogoutRedirectUris;
    private @Nullable String scopes;
    private boolean requirePkce;
    private boolean requireConsent;
    private long accessTokenTtlMinutes = 5;
    private long refreshTokenTtlDays = 30;
    private boolean reuseRefreshTokens;

    public List<String> getGrantTypes() { return grantTypes; }
    public void setGrantTypes(List<String> grantTypes) { this.grantTypes = grantTypes; }

    public @Nullable String getRedirectUris() { return redirectUris; }
    public void setRedirectUris(@Nullable String redirectUris) { this.redirectUris = redirectUris; }

    public @Nullable String getPostLogoutRedirectUris() { return postLogoutRedirectUris; }
    public void setPostLogoutRedirectUris(@Nullable String postLogoutRedirectUris) {
        this.postLogoutRedirectUris = postLogoutRedirectUris;
    }

    public @Nullable String getScopes() { return scopes; }
    public void setScopes(@Nullable String scopes) { this.scopes = scopes; }

    public boolean isRequirePkce() { return requirePkce; }
    public void setRequirePkce(boolean requirePkce) { this.requirePkce = requirePkce; }

    public boolean isRequireConsent() { return requireConsent; }
    public void setRequireConsent(boolean requireConsent) { this.requireConsent = requireConsent; }

    public long getAccessTokenTtlMinutes() { return accessTokenTtlMinutes; }
    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public long getRefreshTokenTtlDays() { return refreshTokenTtlDays; }
    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    public boolean isReuseRefreshTokens() { return reuseRefreshTokens; }
    public void setReuseRefreshTokens(boolean reuseRefreshTokens) {
        this.reuseRefreshTokens = reuseRefreshTokens;
    }
}

package com.stucray.limen.clients;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Controller binding target for the new-client form. Spring's data binder
 * populates this via setters; missing form fields keep the field-initialised
 * defaults below, which preserves the prior {@code @RequestParam(defaultValue = …)}
 * behaviour exactly:
 *
 * <ul>
 *   <li>{@code confidential} defaults to {@code true} (an unchecked checkbox
 *       submits no value, so the default fires) — matches the original
 *       behaviour. Whether unchecking should produce a public client is a
 *       separate UX question outside the scope of this refactor.</li>
 *   <li>Token TTLs default to 5 minutes / 30 days, matching the form's
 *       initial values.</li>
 * </ul>
 *
 * Strings (redirect URIs, post-logout redirect URIs, scopes) are kept as raw
 * separator-delimited input here; parsing into typed collections happens at
 * the controller before building a {@link CreateClientCommand}.
 */
public final class CreateClientForm {

    private @Nullable String displayName;
    private List<String> grantTypes = List.of();
    private @Nullable String redirectUris;
    private @Nullable String postLogoutRedirectUris;
    private @Nullable String scopes;
    private boolean requirePkce;
    private boolean confidential = true;
    private long accessTokenTtlMinutes = 5;
    private long refreshTokenTtlDays = 30;
    private boolean reuseRefreshTokens;

    public @Nullable String getDisplayName() { return displayName; }
    public void setDisplayName(@Nullable String displayName) { this.displayName = displayName; }

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

    public boolean isConfidential() { return confidential; }
    public void setConfidential(boolean confidential) { this.confidential = confidential; }

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

package com.stucray.limen.clients;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Set;

/**
 * Service-layer input for {@link ClientManagementService#createClient}. The
 * controller's {@link CreateClientForm} is responsible for parsing raw
 * request strings (newline-separated redirect URIs, comma-separated scopes,
 * grant-type identifiers) into the typed collections this command carries.
 */
public record CreateClientCommand(
    Long applicationId,
    Long tenantId,
    String displayName,
    Set<AuthorizationGrantType> grantTypes,
    Set<String> redirectUris,
    Set<String> postLogoutRedirectUris,
    Set<String> scopes,
    boolean requirePkce,
    boolean confidential,
    long accessTokenTtlMinutes,
    long refreshTokenTtlDays,
    boolean reuseRefreshTokens
) {}

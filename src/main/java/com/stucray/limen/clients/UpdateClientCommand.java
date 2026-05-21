package com.stucray.limen.clients;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Set;

/**
 * Service-layer input for {@link ClientManagementService#updateClientSettings}.
 * The controller's {@link UpdateClientForm} is responsible for parsing raw
 * request strings (newline-separated redirect URIs, comma- or
 * whitespace-separated scopes, grant-type identifiers) into the typed
 * collections this command carries.
 *
 * <p>Confidentiality is intentionally absent — flipping it requires
 * generating/discarding a client secret and changing the authentication
 * method, which warrants its own flow.
 */
public record UpdateClientCommand(
    String registeredClientId,
    Long tenantId,
    Set<AuthorizationGrantType> grantTypes,
    Set<String> redirectUris,
    Set<String> postLogoutRedirectUris,
    Set<String> scopes,
    boolean requirePkce,
    boolean requireConsent,
    long accessTokenTtlMinutes,
    long refreshTokenTtlDays,
    boolean reuseRefreshTokens
) {}

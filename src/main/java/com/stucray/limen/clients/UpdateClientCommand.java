package com.stucray.limen.clients;

import java.util.Set;

/**
 * Service-layer input for {@link ClientManagementService#updateClientSettings}.
 * The controller's {@link UpdateClientForm} is responsible for parsing raw
 * request strings (newline-separated redirect URIs, comma- or
 * whitespace-separated scopes) into the typed collections this command
 * carries.
 *
 * <p>Grant types and confidentiality are intentionally absent — changing
 * either is a more disruptive operation that warrants its own flow.
 */
public record UpdateClientCommand(
    String registeredClientId,
    Long tenantId,
    Set<String> redirectUris,
    Set<String> postLogoutRedirectUris,
    Set<String> scopes,
    boolean requirePkce,
    boolean requireConsent,
    long accessTokenTtlMinutes,
    long refreshTokenTtlDays,
    boolean reuseRefreshTokens
) {}

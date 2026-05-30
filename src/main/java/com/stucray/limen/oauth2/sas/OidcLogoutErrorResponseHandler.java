package com.stucray.limen.oauth2.sas;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * Failure handler for SAS's OIDC RP-initiated logout endpoint that writes the
 * {@link OAuth2Error} <em>directly</em> as a {@code 400 application/json}
 * response.
 *
 * <p>SAS's default logout failure handler calls
 * {@code response.sendError(400, …)}. In Limen's multi-chain setup that ERROR
 * dispatch is forwarded to {@code /error}, which no chain owns, so it lands on
 * the catch-all {@code DefaultSecurityConfig} chain and is rejected by its
 * {@code anyRequest().denyAll()} — the browser receives a bodyless {@code 403}
 * it renders as a hard error page (#324). The active-session logout path is the
 * one that exposes this, because its extra {@code sub}/{@code sid}-claim
 * validation is the only logout validation that can fail once the
 * {@code post_logout_redirect_uri} is already known-good.
 *
 * <p>Writing the response here keeps it self-contained (no {@code /error}
 * forward) and renderable, mirroring how SAS's token/authorize endpoints
 * already emit their errors — the gap {@link SasServerErrorTranslationFilter}
 * (#293) left open, since that filter only translates thrown
 * {@link RuntimeException}s and deliberately lets
 * {@link OAuth2AuthenticationException} propagate to the per-endpoint handler.
 *
 * <p>The body carries only the public OAuth2 error code + the spec's parameter
 * description + the spec URI — no Limen-internal state.
 */
final class OidcLogoutErrorResponseHandler implements AuthenticationFailureHandler {

    private final HttpMessageConverter<OAuth2Error> errorConverter =
        new OAuth2ErrorHttpMessageConverter();

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) throws IOException {
        OAuth2Error error = (exception instanceof OAuth2AuthenticationException oauthEx)
            ? oauthEx.getError()
            : new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorConverter.write(error, MediaType.APPLICATION_JSON,
            new ServletServerHttpResponse(response));
    }
}

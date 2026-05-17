package com.stucray.limen.oauth2.sas;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Translates uncaught {@link RuntimeException}s from downstream SAS filters
 * into an RFC 6749 §5.2 error response — {@code 500 application/json}
 * {@code {"error":"server_error", ...}}. Without this, a runtime fault inside
 * the token-issuance path (e.g. {@code JwtEncodingException} when the
 * tenant signing key can't be unwrapped) escapes the {@code FilterChainProxy},
 * Spring Boot's {@code ErrorPageFilter} forwards to {@code /error}, and the
 * catch-all default chain's {@code denyAll()} sends {@code 403 [no body]} —
 * a non-RFC-compliant response that hides the actual server fault from
 * relying parties (issue #293).
 *
 * <p>{@link OAuth2AuthenticationException} is intentionally left to propagate
 * so SAS's per-endpoint failure handlers can produce the correct
 * {@code invalid_grant}/{@code invalid_client}/etc. responses.
 *
 * <p>Scoped to the SAS endpoints by the {@link RequestMatcher} the configurer
 * exposes; non-SAS paths handled elsewhere in the chain are unaffected.
 */
final class SasServerErrorTranslationFilter extends OncePerRequestFilter {

    private final HttpMessageConverter<OAuth2Error> errorConverter =
        new OAuth2ErrorHttpMessageConverter();
    private final RequestMatcher endpointsMatcher;

    SasServerErrorTranslationFilter(RequestMatcher endpointsMatcher) {
        this.endpointsMatcher = endpointsMatcher;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (!endpointsMatcher.matches(request) || response.isCommitted()) {
                throw ex;
            }
            OAuth2Error error = new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR,
                "The authorization server encountered an unexpected error.",
                null
            );
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            errorConverter.write(error, MediaType.APPLICATION_JSON,
                new ServletServerHttpResponse(response));
        }
    }
}

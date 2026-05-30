package com.stucray.limen.oauth2.sas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract that an OIDC logout failure is written as a renderable
 * {@code 400 application/json} OAuth2 error body — never delegated to
 * {@code sendError}, which in Limen's multi-chain setup forwards to
 * {@code /error} and surfaces as the catch-all chain's bodyless {@code 403}
 * (#324).
 */
@DisplayName("OidcLogoutErrorResponseHandler writes a renderable 400 OAuth2 error instead of sendError → /error → empty 403")
class OidcLogoutErrorResponseHandlerTest {

    private final OidcLogoutErrorResponseHandler handler = new OidcLogoutErrorResponseHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("OAuth2AuthenticationException → 400 application/json carrying the original OAuth2 error code, body written directly (no sendError)")
    void writesOAuth2ErrorAsJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connect/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2Error error = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "OpenID Connect 1.0 Logout Request Parameter: sid", null);

        handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(error));

        assertThat(response.getStatus()).isEqualTo(400);
        // sendError would have set an error message + scheduled an /error
        // dispatch; writing directly does neither.
        assertThat(response.getErrorMessage()).isNull();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("error").asText()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN);
        assertThat(body.has("error_description")).isTrue();
    }

    @Test
    @DisplayName("a non-OAuth2 AuthenticationException falls back to a generic invalid_request 400 with a body")
    void nonOAuth2ExceptionFallsBackToInvalidRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connect/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).isNull();
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("error").asText()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST);
    }
}

package com.stucray.limen.oauth2.sas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract that uncaught runtime faults in the SAS chain produce
 * RFC 6749 §5.2 {@code {"error":"server_error"}} JSON for the configured
 * endpoint matcher, while OAuth2-specific failures and non-SAS paths are
 * left untouched (issue #293).
 */
@DisplayName("SasServerErrorTranslationFilter writes RFC 6749 §5.2 server_error JSON for uncaught runtime exceptions on SAS endpoints")
class SasServerErrorTranslationFilterTest {

    private final RequestMatcher tokenEndpoint =
        PathPatternRequestMatcher.withDefaults().matcher("/oauth2/token");
    private final SasServerErrorTranslationFilter filter =
        new SasServerErrorTranslationFilter(tokenEndpoint);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("RuntimeException from downstream on /oauth2/token → 500 application/json {\"error\":\"server_error\"} (not empty 403)")
    void runtimeExceptionOnTokenEndpointBecomesServerErrorJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setServletPath("/oauth2/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new RuntimeException("boom — signing key unwrap failed");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("error").asText()).isEqualTo(OAuth2ErrorCodes.SERVER_ERROR);
        assertThat(body.has("error_description")).isTrue();
    }

    @Test
    @DisplayName("OAuth2AuthenticationException propagates so the SAS endpoint's own failure handler can write the canonical OAuth2 error")
    void oauth2AuthenticationExceptionPropagates() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setServletPath("/oauth2/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
            .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(response.getStatus()).isEqualTo(200); // never written
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("RuntimeException on a non-matching path is re-thrown so the rest of the chain can deal with it")
    void runtimeExceptionOnNonMatchingPathRethrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/somewhere/else");
        request.setServletPath("/somewhere/else");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuntimeException boom = new RuntimeException("not for us");
        FilterChain chain = (req, res) -> {
            throw boom;
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
            .isSameAs(boom);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("RuntimeException after the response has been committed is re-thrown rather than mutated")
    void doesNotTouchCommittedResponse() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setServletPath("/oauth2/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuntimeException boom = new RuntimeException("after the bytes flew");
        FilterChain chain = (req, res) -> {
            res.getWriter().write("partial");
            res.flushBuffer();
            throw boom;
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                try {
                    chain.doFilter(req, res);
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e);
                }
            }
        })).isSameAs(boom);
        assertThat(response.getContentAsString()).isEqualTo("partial");
    }
}

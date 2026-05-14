package com.stucray.limen.auth.ott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch-coverage unit tests for {@link MagicLinkBuilder}. Mirrors the shape
 * of {@code TenantIssuerContextFilterUnitTest} since the base-URL derivation
 * logic is the same (and intentionally duplicated rather than shared — see
 * the class javadoc).
 */
@DisplayName("MagicLinkBuilder: branch coverage for default-port handling and absent request context")
class MagicLinkBuilderTest {

    private final MagicLinkBuilder builder = new MagicLinkBuilder();

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("http on the default port 80 produces a magic link with no explicit :80 segment")
    void httpOnPort80OmitsPort() {
        bindRequest("http", "auth.example.com", 80);
        assertThat(builder.build("alpha", "tok-1"))
            .isEqualTo("http://auth.example.com/t/alpha/login/ott?token=tok-1");
    }

    @Test
    @DisplayName("https on the default port 443 produces a magic link with no explicit :443 segment")
    void httpsOnPort443OmitsPort() {
        bindRequest("https", "auth.example.com", 443);
        assertThat(builder.build("alpha", "tok-1"))
            .isEqualTo("https://auth.example.com/t/alpha/login/ott?token=tok-1");
    }

    @Test
    @DisplayName("http on a non-default port (e.g. 8090) keeps the port in the magic link")
    void httpOnNonDefaultPortKeepsPort() {
        bindRequest("http", "localhost", 8090);
        assertThat(builder.build("alpha", "tok-1"))
            .isEqualTo("http://localhost:8090/t/alpha/login/ott?token=tok-1");
    }

    @Test
    @DisplayName("https on a non-default port (e.g. 8443) keeps the port in the magic link")
    void httpsOnNonDefaultPortKeepsPort() {
        bindRequest("https", "auth.example.com", 8443);
        assertThat(builder.build("alpha", "tok-1"))
            .isEqualTo("https://auth.example.com:8443/t/alpha/login/ott?token=tok-1");
    }

    @Test
    @DisplayName("absent request context throws — caller invariant violated, fail loudly rather than emit a broken URL")
    void absentRequestContextThrows() {
        // No bindRequest() — RequestContextHolder is empty.
        assertThatThrownBy(() -> builder.build("alpha", "tok-1"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("active servlet request");
    }

    private static void bindRequest(String scheme, String host, int port) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setScheme(scheme);
        req.setServerName(host);
        req.setServerPort(port);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }
}

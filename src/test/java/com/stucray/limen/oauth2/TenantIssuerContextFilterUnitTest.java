package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Branch-coverage unit tests for {@link TenantIssuerContextFilter}. The
 * happy-path issuer override is exercised indirectly by the OAuth2 routing
 * integration tests; this fills in the no-tenant-scope short-circuit and
 * the default-port branches in {@code buildBaseUrl}, which would silently
 * stamp wrong-issuer claims if broken.
 */
class TenantIssuerContextFilterUnitTest {

    AuthorizationServerSettings baseSettings;
    TenantIssuerContextFilter filter;

    @BeforeEach
    void setUp() {
        baseSettings = AuthorizationServerSettings.builder().build();
        filter = new TenantIssuerContextFilter(baseSettings);
    }

    @AfterEach
    void clearContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void noTenantScopeLeavesContextUntouchedAndProceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(AuthorizationServerContextHolder.getContext()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void httpOnPort80OmitsPortFromIssuer() throws Exception {
        String issuer = issuerFor("http", "auth.example.com", 80, "alpha");
        assertThat(issuer).isEqualTo("http://auth.example.com/t/alpha");
    }

    @Test
    void httpsOnPort443OmitsPortFromIssuer() throws Exception {
        String issuer = issuerFor("https", "auth.example.com", 443, "alpha");
        assertThat(issuer).isEqualTo("https://auth.example.com/t/alpha");
    }

    @Test
    void httpOnNonDefaultPortIncludesPortInIssuer() throws Exception {
        String issuer = issuerFor("http", "localhost", 8080, "alpha");
        assertThat(issuer).isEqualTo("http://localhost:8080/t/alpha");
    }

    @Test
    void httpsOnNonDefaultPortIncludesPortInIssuer() throws Exception {
        String issuer = issuerFor("https", "auth.example.com", 8443, "alpha");
        assertThat(issuer).isEqualTo("https://auth.example.com:8443/t/alpha");
    }

    private String issuerFor(String scheme, String host, int port, String slug) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorize");
        req.setScheme(scheme);
        req.setServerName(host);
        req.setServerPort(port);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        TenantScope.call(slug, 1L, () -> {
            try {
                filter.doFilter(req, res, chain);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        verify(chain, times(1)).doFilter(req, res);
        return AuthorizationServerContextHolder.getContext().getIssuer();
    }
}

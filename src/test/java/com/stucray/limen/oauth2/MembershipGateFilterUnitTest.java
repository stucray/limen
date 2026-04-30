package com.stucray.limen.oauth2;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.management.memberships.ClientMembershipQuery;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Branch-coverage unit tests for {@link MembershipGateFilter}. The
 * integration test ({@code OAuth2AuthorizeMembershipGateIntegrationTest})
 * covers the happy path + denial-with-state; this fills in the parameter-
 * shape edges and {@code resolveRedirectUri} fallbacks that are awkward to
 * exercise through the full SAS flow.
 */
@ExtendWith(MockitoExtension.class)
class MembershipGateFilterUnitTest {

    @Mock RegisteredClientRepository registeredClientRepository;
    @Mock ClientMembershipQuery clientMembershipQuery;

    MembershipGateFilter filter;

    Tenant alpha;
    User alice;
    TenantUserDetails principal;

    @BeforeEach
    void setUp() {
        filter = new MembershipGateFilter(registeredClientRepository, clientMembershipQuery);
        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        alice = new User(10L, 1L, "alice", "hash", true, false, false, LocalDateTime.now());
        principal = new TenantUserDetails(alice, alpha);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAuthorizeRequestPassesThroughWithoutLookup() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/somewhere/else");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(registeredClientRepository, clientMembershipQuery);
    }

    @Test
    void unauthenticatedRequestPassesThroughWithoutLookup() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = newAuthorizeRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(registeredClientRepository, clientMembershipQuery);
    }

    @Test
    void missingClientIdPassesThroughForSasToHandle() throws Exception {
        MockHttpServletRequest req = newAuthorizeRequest();
        // No client_id parameter set.
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        TenantScope.run("alpha", 1L, () -> {
            try {
                filter.doFilter(req, res, chain);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(registeredClientRepository, clientMembershipQuery);
    }

    @Test
    void unknownClientIdPassesThroughForSasToReportInvalidClient() throws Exception {
        MockHttpServletRequest req = newAuthorizeRequest();
        req.setParameter("client_id", "ghost-client");
        given(registeredClientRepository.findByClientId("ghost-client")).willReturn(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        TenantScope.run("alpha", 1L, () -> {
            try {
                filter.doFilter(req, res, chain);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(clientMembershipQuery);
    }

    @Test
    void multipleRegisteredRedirectsAndNoRequestedYields403() throws Exception {
        RegisteredClient client = pkceClientBuilder("multi-redirect-client")
            .redirectUri("http://localhost/cb1")
            .redirectUri("http://localhost/cb2")
            .build();
        given(registeredClientRepository.findByClientId("multi-redirect-client")).willReturn(client);
        given(clientMembershipQuery.hasMembership(10L, client.getId(), 1L)).willReturn(false);

        MockHttpServletRequest req = newAuthorizeRequest();
        req.setParameter("client_id", "multi-redirect-client");
        // No redirect_uri supplied; with multiple registered URIs the filter refuses
        // to guess and falls through to a 403 instead of redirecting.
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        TenantScope.run("alpha", 1L, () -> {
            try {
                filter.doFilter(req, res, chain);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getErrorMessage()).isEqualTo("access_denied");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void singleRegisteredRedirectIsUsedAsFallbackWhenRequestOmitsIt() throws Exception {
        RegisteredClient client = pkceClientBuilder("single-redirect-client")
            .redirectUri("http://localhost/only-callback")
            .build();
        given(registeredClientRepository.findByClientId("single-redirect-client")).willReturn(client);
        given(clientMembershipQuery.hasMembership(10L, client.getId(), 1L)).willReturn(false);

        MockHttpServletRequest req = newAuthorizeRequest();
        req.setParameter("client_id", "single-redirect-client");
        // No redirect_uri supplied; single registered URI is used as fallback.
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        TenantScope.run("alpha", 1L, () -> {
            try {
                filter.doFilter(req, res, chain);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getHeader("Location"))
            .startsWith("http://localhost/only-callback")
            .contains("error=access_denied");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void unboundTenantScopeFallsThroughToAccessDenied() throws Exception {
        RegisteredClient client = pkceClientBuilder("scoped-client")
            .redirectUri("http://localhost/callback")
            .build();
        given(registeredClientRepository.findByClientId("scoped-client")).willReturn(client);

        MockHttpServletRequest req = newAuthorizeRequest();
        req.setParameter("client_id", "scoped-client");
        req.setParameter("redirect_uri", "http://localhost/callback");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // No TenantScope.run wrapper — tenantId() returns null and the && short-circuits.
        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getHeader("Location"))
            .startsWith("http://localhost/callback")
            .contains("error=access_denied");
        verify(chain, never()).doFilter(req, res);
        verifyNoInteractions(clientMembershipQuery);
    }

    private static MockHttpServletRequest newAuthorizeRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorize");
        req.setServletPath("/oauth2/authorize");
        return req;
    }

    private static RegisteredClient.Builder pkceClientBuilder(String clientId) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
    }
}

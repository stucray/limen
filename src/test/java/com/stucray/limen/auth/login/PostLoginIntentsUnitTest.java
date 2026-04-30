package com.stucray.limen.auth.login;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostLoginIntentsUnitTest {

    private static final TenantUrlScheme OAUTH2 = new TenantUrlScheme(
        "oauth2",
        HttpMethod.POST, "/t/*/login",
        Pattern.compile("^/t/([^/]+)(?:/.*)?$"),
        "/t/{slug}/login",
        "/t/{slug}/",
        "/t/{slug}/change-password"
    );

    @Mock RequestCache requestCache;
    @Mock SavedRequest savedRequest;

    Tenant alpha;
    User aliceFresh;
    User aliceMustChange;
    TenantUserDetails freshPrincipal;
    TenantUserDetails mustChangePrincipal;

    MockHttpServletRequest req;
    MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        aliceFresh = new User(10L, 1L, "alice", "hash", true, false, false, LocalDateTime.now());
        aliceMustChange = aliceFresh.withMustChangePassword(true);
        freshPrincipal = new TenantUserDetails(aliceFresh, alpha);
        mustChangePrincipal = new TenantUserDetails(aliceMustChange, alpha);
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
    }

    @Test
    void passwordChangeRequiredRedirectsWhenFlagSet() {
        PostLoginIntent intent = PostLoginIntents.passwordChangeRequired();

        String url = intent.resolve(req, res, mustChangePrincipal, OAUTH2);

        assertThat(url).isEqualTo("/t/alpha/change-password");
    }

    @Test
    void passwordChangeRequiredFallsThroughWhenFlagNotSet() {
        PostLoginIntent intent = PostLoginIntents.passwordChangeRequired();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
    }

    @Test
    void resumeOAuth2AuthorizeReturnsTenantPrefixedUrlAndClearsCache() {
        given(requestCache.getRequest(req, res)).willReturn(savedRequest);
        given(savedRequest.getRedirectUrl())
            .willReturn("http://localhost/oauth2/authorize?client_id=foo&state=bar");
        PostLoginIntent intent = PostLoginIntents.resumeOAuth2Authorize(requestCache);

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isEqualTo(
            "http://localhost/t/alpha/oauth2/authorize?client_id=foo&state=bar");
        verify(requestCache, times(1)).removeRequest(req, res);
    }

    @Test
    void resumeOAuth2AuthorizeFallsThroughWhenNoSavedRequest() {
        given(requestCache.getRequest(req, res)).willReturn(null);
        PostLoginIntent intent = PostLoginIntents.resumeOAuth2Authorize(requestCache);

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
        verify(requestCache, never()).removeRequest(req, res);
    }

    @Test
    void resumeOAuth2AuthorizeFallsThroughWhenSavedRequestIsNotAuthorize() {
        given(requestCache.getRequest(req, res)).willReturn(savedRequest);
        given(savedRequest.getRedirectUrl()).willReturn("http://localhost/some-other-page");
        PostLoginIntent intent = PostLoginIntents.resumeOAuth2Authorize(requestCache);

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
        verify(requestCache, never()).removeRequest(req, res);
    }

    @Test
    void tenantHomeAlwaysReturnsHomeUrlForPrincipalSlug() {
        PostLoginIntent intent = PostLoginIntents.tenantHome();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isEqualTo("/t/alpha/");
    }
}

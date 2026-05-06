package com.stucray.limen.auth.login;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.TenantOttAuthentication;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostLoginIntents (success-handler chain)")
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
        aliceFresh = new User(10L, 1L, "alice", "hash", true, false, false, true, LocalDateTime.now());
        aliceMustChange = aliceFresh.withMustChangePassword(true);
        freshPrincipal = new TenantUserDetails(aliceFresh, alpha);
        mustChangePrincipal = new TenantUserDetails(aliceMustChange, alpha);
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("passwordChangeRequired: redirects to the tenant change-password URL when must_change_password is set")
    void passwordChangeRequiredRedirectsWhenFlagSet() {
        PostLoginIntent intent = PostLoginIntents.passwordChangeRequired();

        String url = intent.resolve(req, res, mustChangePrincipal, OAUTH2);

        assertThat(url).isEqualTo("/t/alpha/change-password");
    }

    @Test
    @DisplayName("passwordChangeRequired: returns null (falls through) when the flag is not set")
    void passwordChangeRequiredFallsThroughWhenFlagNotSet() {
        PostLoginIntent intent = PostLoginIntents.passwordChangeRequired();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("resumeOAuth2Authorize: rewrites a saved /oauth2/authorize URL to /t/{slug}/oauth2/authorize and clears the cache")
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
    @DisplayName("resumeOAuth2Authorize: returns null and leaves the cache untouched when there is no saved request")
    void resumeOAuth2AuthorizeFallsThroughWhenNoSavedRequest() {
        given(requestCache.getRequest(req, res)).willReturn(null);
        PostLoginIntent intent = PostLoginIntents.resumeOAuth2Authorize(requestCache);

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
        verify(requestCache, never()).removeRequest(req, res);
    }

    @Test
    @DisplayName("resumeOAuth2Authorize: returns null when the saved request is not for /oauth2/authorize")
    void resumeOAuth2AuthorizeFallsThroughWhenSavedRequestIsNotAuthorize() {
        given(requestCache.getRequest(req, res)).willReturn(savedRequest);
        given(savedRequest.getRedirectUrl()).willReturn("http://localhost/some-other-page");
        PostLoginIntent intent = PostLoginIntents.resumeOAuth2Authorize(requestCache);

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
        verify(requestCache, never()).removeRequest(req, res);
    }

    @Test
    @DisplayName("passwordChangeAfterReset: redirects to change-password when the current Authentication is a TenantOttAuthentication carrying PASSWORD_RESET")
    void passwordChangeAfterResetRedirectsWhenAuthIsResetIntent() {
        setSecurityContext(new TenantOttAuthentication(
            freshPrincipal, List.of(), OttIntent.PASSWORD_RESET));
        PostLoginIntent intent = PostLoginIntents.passwordChangeAfterReset();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isEqualTo("/t/alpha/change-password");
    }

    @Test
    @DisplayName("passwordChangeAfterReset: returns null when the TenantOttAuthentication carries VERIFY_EMAIL — only the reset intent triggers the redirect")
    void passwordChangeAfterResetFallsThroughForVerifyEmailIntent() {
        setSecurityContext(new TenantOttAuthentication(
            freshPrincipal, List.of(), OttIntent.VERIFY_EMAIL));
        PostLoginIntent intent = PostLoginIntents.passwordChangeAfterReset();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("passwordChangeAfterReset: returns null when the current Authentication is a plain UsernamePasswordAuthenticationToken — covers the post-rotation case")
    void passwordChangeAfterResetFallsThroughForPlainAuth() {
        setSecurityContext(new UsernamePasswordAuthenticationToken(freshPrincipal, null, List.of()));
        PostLoginIntent intent = PostLoginIntents.passwordChangeAfterReset();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("passwordChangeAfterReset: returns null when no Authentication is set on the SecurityContext")
    void passwordChangeAfterResetFallsThroughWhenNoAuth() {
        PostLoginIntent intent = PostLoginIntents.passwordChangeAfterReset();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("tenantHome: always returns /t/{principal-slug}/ — the catch-all default")
    void tenantHomeAlwaysReturnsHomeUrlForPrincipalSlug() {
        PostLoginIntent intent = PostLoginIntents.tenantHome();

        String url = intent.resolve(req, res, freshPrincipal, OAUTH2);

        assertThat(url).isEqualTo("/t/alpha/");
    }

    private static void setSecurityContext(org.springframework.security.core.Authentication auth) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }
}

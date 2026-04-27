package com.stucray.limen.auth;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Unit-level cookie encode/decode round-trip checks for the slug segment.
 * Avoids loading a Spring context — exercises pure logic only.
 */
@ExtendWith(MockitoExtension.class)
class TenantPersistentTokenBasedRememberMeServicesUnitTest {

    @Mock TenantUserDetailsService userDetailsService;
    @Mock TenantPersistentTokenRepository tokenRepository;
    @Mock TenantRepository tenantRepository;

    TenantPersistentTokenBasedRememberMeServices services;

    Tenant alpha;
    User alice;

    @BeforeEach
    void setUp() {
        services = new TenantPersistentTokenBasedRememberMeServices(
            "test-key", userDetailsService, tokenRepository, tenantRepository);
        services.setTokenValiditySeconds(3600);

        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        alice = new User(10L, 1L, "alice", "hash", true, false, false, LocalDateTime.now());
    }

    @Test
    void onLoginSuccessIssuesCookieWithSeriesTokenSlug() {
        TenantUserDetails principal = new TenantUserDetails(alice, alpha);
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken
            .authenticated(principal, null, principal.getAuthorities());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/t/alpha/login");
        req.setContextPath("");
        MockHttpServletResponse res = new MockHttpServletResponse();

        services.onLoginSuccess(req, res, auth);

        Cookie cookie = res.getCookie("remember-me");
        assertThat(cookie).isNotNull();
        String decoded = new String(java.util.Base64.getDecoder().decode(cookie.getValue()));
        String[] parts = decoded.split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEqualTo("alpha");
    }

    @Test
    void processAutoLoginRejectsCookieWithMismatchedSlug() {
        // Build a base64-encoded `series:token:slug` cookie value where slug = beta
        String value = base64("ser-1:tok-1:beta");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/t/alpha/protected");
        req.setCookies(new Cookie("remember-me", value));
        MockHttpServletResponse res = new MockHttpServletResponse();

        // AbstractRememberMeServices.autoLogin catches InvalidCookieException, cancels
        // the cookie, and returns null. Both observable effects are asserted.
        assertThat(services.autoLogin(req, res)).isNull();
        Cookie cancelled = res.getCookie("remember-me");
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getMaxAge()).isZero();
    }

    @Test
    void processAutoLoginRejectsCookieWithWrongSegmentCount() {
        // Build a 2-segment cookie (legacy / pre-V2 format) — also rejected.
        String value = base64("ser-1:tok-1");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/t/alpha/protected");
        req.setCookies(new Cookie("remember-me", value));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(services.autoLogin(req, res)).isNull();
        Cookie cancelled = res.getCookie("remember-me");
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getMaxAge()).isZero();
    }

    @Test
    void processAutoLoginRejectsUnknownSlug() {
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.empty());

        String value = base64("ser-1:tok-1:alpha");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/t/alpha/protected");
        req.setCookies(new Cookie("remember-me", value));
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Returns null when authentication fails.
        assertThat(services.autoLogin(req, res)).isNull();
    }

    private static String base64(String s) {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes());
    }
}

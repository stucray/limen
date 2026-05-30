package com.stucray.limen.ui.support;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process OAuth2 relying party used by end-to-end UI tests.
 *
 * <p>The controller's only job is to capture the {@code ?code=…&state=…} the
 * authorization server redirects to it. The test method does the token
 * exchange + userinfo call directly so assertions stay next to expectations;
 * this is intentionally a dumb code-capturer, not a full RP.
 *
 * <p>A dedicated {@link SecurityFilterChain} permits anonymous access to
 * {@code /test-rp/**}; without it, the catch-all {@code DefaultSecurityConfig}
 * (denies anything not in its allow-list) would 403 the redirect.
 */
@TestConfiguration
public class TestOAuth2RelyingParty {

    @Bean
    @Order(0)
    SecurityFilterChain testRpSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/test-rp/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .build();
    }

    /**
     * Captures the (state → code) pair the RP receives from the authorization
     * server. Tests poll {@link #awaitCallback(String, Duration)} on a known
     * {@code state} to retrieve the code without racing the redirect.
     */
    @RestController
    @RequestMapping("/test-rp")
    public static class TestRpCallbackController {

        private final ConcurrentMap<String, String> codesByState = new ConcurrentHashMap<>();

        /**
         * Post-logout landing page. RP-initiated logout's
         * {@code post_logout_redirect_uri} points here so the browser lands on
         * an origin the test controls rather than an external one that would
         * fail to connect. The body is irrelevant — tests assert on the URL the
         * browser reaches.
         */
        @GetMapping("/logged-out")
        public String loggedOut(@RequestParam(required = false) @Nullable String state) {
            return "logged-out" + (state == null ? "" : ":" + state);
        }

        @GetMapping("/callback")
        public String callback(
            @RequestParam(required = false) @Nullable String code,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String error
        ) {
            if (code != null && state != null) {
                codesByState.put(state, code);
            }
            // Returned body is irrelevant — the test waits on the redirect
            // happening, not on this response.
            return error == null ? "ok" : "error:" + error;
        }

        /**
         * Polls until the callback for {@code state} arrives or the timeout
         * elapses. Returns the auth code. Throws {@link IllegalStateException}
         * if no callback arrives in time.
         */
        public String awaitCallback(String state, Duration timeout) {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadlineNanos) {
                String code = codesByState.remove(state);
                if (code != null) return code;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for callback", e);
                }
            }
            throw new IllegalStateException("No callback received for state=" + state + " within " + timeout);
        }
    }
}

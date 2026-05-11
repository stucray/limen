package com.stucray.limen.oauth2;

import com.stucray.limen.auth.login.TenantLogin;
import com.stucray.limen.auth.login.TenantUrlScheme;
import com.stucray.limen.auth.ott.TenantAwareOneTimeTokenService;
import com.stucray.limen.auth.ott.TenantOttAuthenticationProvider;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * SecurityFilterChain for the end-user tenant surface ({@code /t/**}): login,
 * change-password, post-login home, and logout. Sits between the SAS chain
 * (HIGHEST_PRECEDENCE, which still claims {@code /t/*&#47;oauth2/...}) and the
 * management chain (Order 2). Form-login and logout wiring are both delegated to
 * {@link TenantLogin}; everything left here is distinctive to this surface
 * (URL scope, CSRF, request cache, tenant-aware entry point).
 *
 * <p>One-Time Token Login is wired here as well: the magic link issued during
 * email verification (and, in slice #126, during password reset) lands at
 * {@code /t/{slug}/login/ott} where {@link TenantOttAuthenticationProvider}
 * exchanges the token for an authenticated session. Post-OTT dispatch reuses
 * the same {@link TenantLogin#successHandlerFor intent chain} as form login
 * so email-verification, must-change-password, and saved-/oauth2/authorize-resume
 * behave identically across surfaces.
 */
@Configuration
@Order(1)
class OAuth2LoginSecurityConfig {

    private final TenantLogin login;
    private final TenantUrlScheme oauth2UrlScheme;
    private final TenantAwareOneTimeTokenService oneTimeTokenService;
    private final OneTimeTokenGenerationSuccessHandler ottGenerationSuccessHandler;
    private final TenantOttAuthenticationProvider ottAuthenticationProvider;

    OAuth2LoginSecurityConfig(
        TenantLogin login,
        @Qualifier("oauth2UrlScheme") TenantUrlScheme oauth2UrlScheme,
        TenantAwareOneTimeTokenService oneTimeTokenService,
        OneTimeTokenGenerationSuccessHandler ottGenerationSuccessHandler,
        TenantOttAuthenticationProvider ottAuthenticationProvider
    ) {
        this.login = login;
        this.oauth2UrlScheme = oauth2UrlScheme;
        this.oneTimeTokenService = oneTimeTokenService;
        this.ottGenerationSuccessHandler = ottGenerationSuccessHandler;
        this.ottAuthenticationProvider = ottAuthenticationProvider;
    }

    @Bean
    SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http) throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

        login.applyTo(http, oauth2UrlScheme);
        login.applyLogoutTo(http, oauth2UrlScheme);

        http
            .securityMatcher("/t/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/t/*/login").permitAll()
                .requestMatchers("/t/*/login/ott").permitAll()
                .requestMatchers("/t/*/check-inbox").permitAll()
                .requestMatchers("/t/*/resend-verification").permitAll()
                .requestMatchers("/t/*/forgot-password").permitAll()
                .anyRequest().authenticated()
            )
            .requestCache(rc -> rc.requestCache(requestCache))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                TenantLoginUrlAuthenticationEntryPoint.fromUrl()
            ))
            .oneTimeTokenLogin(ott -> ott
                .tokenService(oneTimeTokenService)
                .tokenGenerationSuccessHandler(ottGenerationSuccessHandler)
                .authenticationProvider(ottAuthenticationProvider)
                .loginProcessingUrl("/t/*/login/ott")
                // Spring's default submit page filter renders a form whose action
                // is the literal loginProcessingUrl ("/t/*/login/ott") — the
                // unexpanded wildcard breaks the form. {@code OttSubmitController}
                // renders the equivalent page with the resolved slug.
                .showDefaultSubmitPage(false)
                .successHandler(login.successHandlerFor(oauth2UrlScheme))
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            );

        return http.build();
    }
}

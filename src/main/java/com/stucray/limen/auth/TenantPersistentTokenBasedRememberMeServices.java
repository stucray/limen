package com.stucray.limen.auth;

import com.stucray.limen.user.TenantUserDetails;

import com.stucray.limen.auth.login.TenantUrlScheme;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Tenant-scoped remember-me. Cookie value is encoded as
 * {@code series:token:slug} (Spring's {@link AbstractRememberMeServices}
 * concatenates the array elements with the separator, defaulting to {@code :}).
 *
 * The slug is captured from the principal at login time and embedded in the
 * cookie. At auto-login time, the slug is decoded and cross-checked against
 * the URL's tenant slug; mismatch is rejected as
 * {@link InvalidCookieException}. Storage is keyed on
 * {@code (tenant_id, series)} via {@link TenantPersistentTokenRepository}.
 *
 * @see RememberMeServices
 */
public final class TenantPersistentTokenBasedRememberMeServices extends AbstractRememberMeServices {

    public static final int DEFAULT_SERIES_LENGTH = 16;
    public static final int DEFAULT_TOKEN_LENGTH = 16;
    /** series, token, slug — see the cookie format note above. */
    private static final int COOKIE_TOKEN_FIELDS = 3;

    private final TenantPersistentTokenRepository tokenRepository;
    private final TenantUserDetailsService tenantUserDetailsService;
    private final TenantRepository tenantRepository;
    private final List<TenantUrlScheme> schemes;
    private final SecureRandom random = new SecureRandom();

    TenantPersistentTokenBasedRememberMeServices(
        String key,
        TenantUserDetailsService userDetailsService,
        TenantPersistentTokenRepository tokenRepository,
        TenantRepository tenantRepository,
        List<TenantUrlScheme> schemes
    ) {
        super(key, userDetailsService);
        this.tokenRepository = tokenRepository;
        this.tenantUserDetailsService = userDetailsService;
        this.tenantRepository = tenantRepository;
        this.schemes = schemes;
    }

    @Override
    protected void onLoginSuccess(
        HttpServletRequest request, HttpServletResponse response, Authentication auth
    ) {
        TenantUserDetails principal = (TenantUserDetails) Objects.requireNonNull(auth.getPrincipal());
        String email = principal.displayEmail();
        String slug = principal.tenantSlug();
        Long tenantId = principal.tenantId();
        String series = encodeBase64(seriesLength());
        String tokenValue = encodeBase64(tokenLength());
        Date now = new Date();
        try {
            tokenRepository.createNewToken(
                new PersistentRememberMeToken(email, series, tokenValue, now), tenantId);
        } catch (Exception ex) {
            this.logger.error("Failed to save persistent token", ex);
            return;
        }
        setCookie(new String[]{series, tokenValue, slug}, getTokenValiditySeconds(), request, response);
    }

    @Override
    protected UserDetails processAutoLoginCookie(
        String[] cookieTokens, HttpServletRequest request, HttpServletResponse response
    ) {
        if (cookieTokens.length != COOKIE_TOKEN_FIELDS) {
            throw new InvalidCookieException(
                "Cookie token did not contain " + COOKIE_TOKEN_FIELDS
                    + " tokens (series, token, slug); got " + cookieTokens.length);
        }
        CookieFields fields = new CookieFields(cookieTokens[0], cookieTokens[1], cookieTokens[2]);

        String urlSlug = extractUrlSlug(request.getRequestURI());
        if (urlSlug != null && !urlSlug.equals(fields.slug())) {
            throw new InvalidCookieException(
                "Cookie slug '" + fields.slug() + "' does not match URL slug '" + urlSlug + "'");
        }

        Tenant tenant = tenantRepository.findBySlug(fields.slug())
            .orElseThrow(() -> new RememberMeAuthenticationException(
                "Unknown tenant slug in remember-me cookie: " + fields.slug()));
        TenantPersistentRememberMeToken token = locateAndValidateToken(fields, tenant);
        rotateToken(fields, token, request, response);
        return tenantUserDetailsService.loadByEmailAndSlug(token.getUsername(), fields.slug());
    }

    private TenantPersistentRememberMeToken locateAndValidateToken(CookieFields fields, Tenant tenant) {
        TenantPersistentRememberMeToken token = tokenRepository.getTokenForSeries(fields.series(), tenant.id());
        if (token == null) {
            throw new RememberMeAuthenticationException(
                "No persistent token found for series id: " + fields.series());
        }
        if (!fields.token().equals(token.getTokenValue())) {
            tokenRepository.removeUserTokens(token.getUsername(), token.getTenantId());
            throw new CookieTheftException(this.messages.getMessage(
                "PersistentTokenBasedRememberMeServices.cookieStolen",
                "Invalid remember-me token (Series/token) mismatch. Implies previous cookie theft attack."));
        }
        if (token.getDate().getTime() + getTokenValiditySeconds() * 1000L < System.currentTimeMillis()) {
            throw new RememberMeAuthenticationException("Remember-me login has expired");
        }
        return token;
    }

    private void rotateToken(
        CookieFields fields, TenantPersistentRememberMeToken token,
        HttpServletRequest request, HttpServletResponse response
    ) {
        String newTokenValue = encodeBase64(tokenLength());
        try {
            tokenRepository.updateToken(fields.series(), token.getTenantId(), newTokenValue, new Date());
            setCookie(new String[]{fields.series(), newTokenValue, fields.slug()},
                getTokenValiditySeconds(), request, response);
        } catch (Exception ex) {
            this.logger.error("Failed to update token", ex);
            throw new RememberMeAuthenticationException("Autologin failed due to data access problem", ex);
        }
    }

    private record CookieFields(String series, String token, String slug) {}

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, @Nullable Authentication auth) {
        super.logout(request, response, auth);
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            tokenRepository.removeUserTokens(details.displayEmail(), details.tenantId());
        }
    }

    private @Nullable String extractUrlSlug(String uri) {
        for (TenantUrlScheme scheme : schemes) {
            String slug = scheme.slugFrom(uri);
            if (slug != null) return slug;
        }
        return null;
    }

    private String encodeBase64(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private int seriesLength() { return DEFAULT_SERIES_LENGTH; }
    private int tokenLength()  { return DEFAULT_TOKEN_LENGTH; }
}

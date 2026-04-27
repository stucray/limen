package com.stucray.limen.auth;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern OAUTH2_SLUG = Pattern.compile("^/t/([^/]+)/.*");
    private static final Pattern MGMT_SLUG = Pattern.compile("^/manage/t/([^/]+)/.*");

    private final TenantPersistentTokenRepository tokenRepository;
    private final TenantUserDetailsService tenantUserDetailsService;
    private final TenantRepository tenantRepository;
    private final SecureRandom random = new SecureRandom();

    public TenantPersistentTokenBasedRememberMeServices(
        String key,
        TenantUserDetailsService userDetailsService,
        TenantPersistentTokenRepository tokenRepository,
        TenantRepository tenantRepository
    ) {
        super(key, userDetailsService);
        this.tokenRepository = tokenRepository;
        this.tenantUserDetailsService = userDetailsService;
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void onLoginSuccess(
        HttpServletRequest request, HttpServletResponse response, Authentication auth
    ) {
        TenantUserDetails principal = (TenantUserDetails) auth.getPrincipal();
        String username = principal.displayUsername();
        String slug = principal.tenantSlug();
        Long tenantId = principal.tenantId();
        String series = encodeBase64(seriesLength());
        String tokenValue = encodeBase64(tokenLength());
        Date now = new Date();
        try {
            tokenRepository.createNewToken(
                new PersistentRememberMeToken(username, series, tokenValue, now), tenantId);
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
        if (cookieTokens.length != 3) {
            throw new InvalidCookieException(
                "Cookie token did not contain 3 tokens (series, token, slug); got " + cookieTokens.length);
        }
        String presentedSeries = cookieTokens[0];
        String presentedToken = cookieTokens[1];
        String cookieSlug = cookieTokens[2];

        String urlSlug = extractUrlSlug(request.getRequestURI());
        if (urlSlug != null && !urlSlug.equals(cookieSlug)) {
            throw new InvalidCookieException(
                "Cookie slug '" + cookieSlug + "' does not match URL slug '" + urlSlug + "'");
        }

        Tenant tenant = tenantRepository.findBySlug(cookieSlug).orElse(null);
        if (tenant == null) {
            throw new RememberMeAuthenticationException("Unknown tenant slug in remember-me cookie: " + cookieSlug);
        }

        TenantPersistentRememberMeToken token = tokenRepository.getTokenForSeries(presentedSeries, tenant.id());
        if (token == null) {
            throw new RememberMeAuthenticationException(
                "No persistent token found for series id: " + presentedSeries);
        }
        if (!presentedToken.equals(token.getTokenValue())) {
            tokenRepository.removeUserTokens(token.getUsername(), token.getTenantId());
            throw new CookieTheftException(this.messages.getMessage(
                "PersistentTokenBasedRememberMeServices.cookieStolen",
                "Invalid remember-me token (Series/token) mismatch. Implies previous cookie theft attack."));
        }
        if (token.getDate().getTime() + getTokenValiditySeconds() * 1000L < System.currentTimeMillis()) {
            throw new RememberMeAuthenticationException("Remember-me login has expired");
        }

        String newTokenValue = encodeBase64(tokenLength());
        try {
            tokenRepository.updateToken(presentedSeries, token.getTenantId(), newTokenValue, new Date());
            setCookie(new String[]{presentedSeries, newTokenValue, cookieSlug},
                getTokenValiditySeconds(), request, response);
        } catch (Exception ex) {
            this.logger.error("Failed to update token", ex);
            throw new RememberMeAuthenticationException("Autologin failed due to data access problem");
        }

        return tenantUserDetailsService.loadByUsernameAndSlug(token.getUsername(), cookieSlug);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        super.logout(request, response, auth);
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            tokenRepository.removeUserTokens(details.displayUsername(), details.tenantId());
        }
    }

    private static String extractUrlSlug(String uri) {
        Matcher m = MGMT_SLUG.matcher(uri);
        if (m.matches()) return m.group(1);
        m = OAUTH2_SLUG.matcher(uri);
        return m.matches() ? m.group(1) : null;
    }

    private String encodeBase64(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return new String(Base64.getEncoder().encode(bytes));
    }

    private int seriesLength() { return DEFAULT_SERIES_LENGTH; }
    private int tokenLength()  { return DEFAULT_TOKEN_LENGTH; }
}

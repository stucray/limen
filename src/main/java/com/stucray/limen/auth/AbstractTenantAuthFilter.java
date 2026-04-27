package com.stucray.limen.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.function.Function;

/**
 * Base class for tenant-scoped form-login filters. Builds a {@link TenantAuthToken}
 * from the URL path slug + form fields and hands it to the shared {@link AuthenticationManager}.
 * Subclasses parameterise the URL pattern, the slug-extraction strategy, and the
 * success/failure handlers.
 */
public abstract class AbstractTenantAuthFilter extends AbstractAuthenticationProcessingFilter {

    private final Function<HttpServletRequest, String> slugExtractor;

    protected AbstractTenantAuthFilter(
        RequestMatcher matcher,
        AuthenticationManager authenticationManager,
        Function<HttpServletRequest, String> slugExtractor
    ) {
        super(matcher, authenticationManager);
        this.slugExtractor = slugExtractor;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
        throws AuthenticationException {
        String slug = slugExtractor.apply(request);
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        return getAuthenticationManager().authenticate(new TenantAuthToken(slug, username, password));
    }
}

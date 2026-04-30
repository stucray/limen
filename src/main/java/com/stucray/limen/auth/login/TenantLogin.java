package com.stucray.limen.auth.login;

import com.stucray.limen.auth.TenantAuthToken;
import com.stucray.limen.auth.TenantPersistentTokenBasedRememberMeServices;
import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.oauth2.TenantAccessFilter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deep module owning the entire login pipeline for tenant-scoped form login:
 * form-filter wiring, authentication delegation, post-login dispatch, remember-me
 * hookup, and cross-tenant force-logout. Each registered surface becomes a
 * one-liner: {@code login.applyTo(http, scheme)}.
 *
 * Immutable. {@link #withIntents(List)} and {@link #withRememberMe(boolean)} return
 * new instances so the shared bean cannot be mutated by one chain and surprise another.
 */
public final class TenantLogin {

    private final AuthenticationManager authenticationManager;
    private final TenantPersistentTokenBasedRememberMeServices rememberMeServices;
    private final String rememberMeKey;
    private final List<PostLoginIntent> intents;
    private final boolean rememberMeEnabled;

    public TenantLogin(
        AuthenticationManager authenticationManager,
        TenantPersistentTokenBasedRememberMeServices rememberMeServices,
        String rememberMeKey,
        List<PostLoginIntent> intents
    ) {
        this(authenticationManager, rememberMeServices, rememberMeKey, intents, true);
    }

    private TenantLogin(
        AuthenticationManager authenticationManager,
        TenantPersistentTokenBasedRememberMeServices rememberMeServices,
        String rememberMeKey,
        List<PostLoginIntent> intents,
        boolean rememberMeEnabled
    ) {
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
        this.rememberMeKey = rememberMeKey;
        this.intents = List.copyOf(intents);
        this.rememberMeEnabled = rememberMeEnabled;
    }

    @PostConstruct
    void verifyOrderUniqueness() {
        Set<Integer> seen = new HashSet<>();
        for (PostLoginIntent intent : intents) {
            Order order = AnnotationUtils.findAnnotation(intent.getClass(), Order.class);
            if (order == null) continue;
            if (!seen.add(order.value())) {
                throw new IllegalStateException(
                    "Duplicate @Order(" + order.value() + ") on PostLoginIntent beans");
            }
        }
    }

    public TenantLogin withIntents(List<PostLoginIntent> newIntents) {
        return new TenantLogin(authenticationManager, rememberMeServices, rememberMeKey, newIntents, rememberMeEnabled);
    }

    public TenantLogin withRememberMe(boolean enabled) {
        return new TenantLogin(authenticationManager, rememberMeServices, rememberMeKey, intents, enabled);
    }

    public List<PostLoginIntent> intents() {
        return intents;
    }

    public boolean rememberMeEnabled() {
        return rememberMeEnabled;
    }

    /** Build the form-login filter for {@code scheme}. */
    public AbstractAuthenticationProcessingFilter filter(TenantUrlScheme scheme) {
        TenantLoginFilter filter = new TenantLoginFilter(scheme, authenticationManager);
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        if (rememberMeEnabled) {
            filter.setRememberMeServices(rememberMeServices);
        }
        filter.setAuthenticationSuccessHandler(new IntentChainSuccessHandler(intents, scheme));
        filter.setAuthenticationFailureHandler(failureHandler(scheme));
        return filter;
    }

    /** Wire the form filter, cross-tenant defence, and remember-me onto {@code http}. */
    public HttpSecurity applyTo(HttpSecurity http, TenantUrlScheme scheme) throws Exception {
        http
            .addFilterAt(filter(scheme), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new TenantAccessFilter(), SecurityContextHolderFilter.class);
        if (rememberMeEnabled) {
            http.rememberMe(rm -> rm
                .rememberMeServices(rememberMeServices)
                .key(rememberMeKey));
        }
        return http;
    }

    private static AuthenticationFailureHandler failureHandler(TenantUrlScheme scheme) {
        return (req, res, ex) -> {
            String slug = scheme.slugFrom(req);
            // slug is non-null here because the failure handler only fires on a request
            // that already matched scheme.loginMatcher().
            res.sendRedirect(req.getContextPath() + scheme.loginUrl(slug) + "?error");
        };
    }

    private static final class TenantLoginFilter extends AbstractAuthenticationProcessingFilter {
        private final TenantUrlScheme scheme;

        TenantLoginFilter(TenantUrlScheme scheme, AuthenticationManager authManager) {
            super(scheme.loginMatcher(), authManager);
            this.scheme = scheme;
        }

        @Override
        public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
            String slug = scheme.slugFrom(request);
            String username = request.getParameter("username");
            String password = request.getParameter("password");
            return getAuthenticationManager().authenticate(new TenantAuthToken(slug, username, password));
        }
    }

    private static final class IntentChainSuccessHandler implements AuthenticationSuccessHandler {
        private final List<PostLoginIntent> intents;
        private final TenantUrlScheme scheme;
        private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

        IntentChainSuccessHandler(List<PostLoginIntent> intents, TenantUrlScheme scheme) {
            this.intents = intents;
            this.scheme = scheme;
        }

        @Override
        public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication auth
        ) throws IOException {
            TenantUserDetails principal = (TenantUserDetails) auth.getPrincipal();
            for (PostLoginIntent intent : intents) {
                String url = intent.resolve(request, response, principal, scheme);
                if (url != null) {
                    redirectStrategy.sendRedirect(request, response, url);
                    return;
                }
            }
            // Unreachable: the default chain terminates with tenantHome().
            throw new IllegalStateException("PostLoginIntent chain did not produce a redirect");
        }
    }
}

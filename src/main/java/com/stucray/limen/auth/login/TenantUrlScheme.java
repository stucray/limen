package com.stucray.limen.auth.login;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-surface URL conventions for a tenant-scoped login. The same scheme is used
 * to match login form submissions, derive the slug from any tenant-scoped URL,
 * and produce surface-appropriate redirect targets (login page, home, change-password).
 *
 * Exposed as a {@code @Bean} so integration tests can register synthetic schemes
 * without mocking.
 */
public record TenantUrlScheme(
    String name,
    HttpMethod loginMethod,
    String loginPathPattern,
    Pattern slugPattern,
    String loginUrlTemplate,
    String homeUrlTemplate,
    String changePasswordUrlTemplate
) {
    /** Matcher for the login form submission. */
    public RequestMatcher loginMatcher() {
        return PathPatternRequestMatcher.withDefaults().matcher(loginMethod, loginPathPattern);
    }

    /** Extract the slug from a request URI, or {@code null} if the URI does not match this scheme. */
    public String slugFrom(HttpServletRequest request) {
        return request == null ? null : slugFrom(request.getRequestURI());
    }

    public String slugFrom(String uri) {
        if (uri == null) return null;
        Matcher m = slugPattern.matcher(uri);
        return m.matches() ? m.group(1) : null;
    }

    public String loginUrl(String slug) {
        return render(loginUrlTemplate, slug);
    }

    public String homeUrl(String slug) {
        return render(homeUrlTemplate, slug);
    }

    public String changePasswordUrl(String slug) {
        return render(changePasswordUrlTemplate, slug);
    }

    private static String render(String template, String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("slug must be non-empty");
        }
        return template.replace("{slug}", slug);
    }
}

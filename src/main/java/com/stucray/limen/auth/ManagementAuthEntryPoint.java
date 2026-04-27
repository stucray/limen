package com.stucray.limen.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redirects unauthenticated requests to the matching tenant's management login,
 * falling back to /manage/t/system/login when no slug is recoverable from the URL.
 */
public final class ManagementAuthEntryPoint implements AuthenticationEntryPoint {

    private static final Pattern SLUG_PATTERN = Pattern.compile("/manage/t/([^/]+)/.*");

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        Matcher matcher = SLUG_PATTERN.matcher(request.getRequestURI());
        String slug = matcher.matches() ? matcher.group(1) : "system";
        response.sendRedirect(request.getContextPath() + "/manage/t/" + slug + "/login");
    }
}

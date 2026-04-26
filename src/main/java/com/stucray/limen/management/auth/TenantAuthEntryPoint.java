package com.stucray.limen.management.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TenantAuthEntryPoint implements AuthenticationEntryPoint {

    private static final Pattern SLUG_PATTERN = Pattern.compile("/manage/t/([^/]+)/.*");

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        String uri = request.getRequestURI();
        Matcher matcher = SLUG_PATTERN.matcher(uri);
        String slug = matcher.matches() ? matcher.group(1) : "system";
        response.sendRedirect(request.getContextPath() + "/manage/t/" + slug + "/login");
    }
}

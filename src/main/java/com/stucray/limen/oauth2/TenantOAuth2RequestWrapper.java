package com.stucray.limen.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.jspecify.annotations.Nullable;

/**
 * Strips the leading /t/{slug} segment from the request URI so that Spring Authorization Server
 * sees standard endpoint paths like /oauth2/token instead of /t/acme/oauth2/token.
 */
public class TenantOAuth2RequestWrapper extends HttpServletRequestWrapper {

    private final String strippedUri;
    private final String strippedServletPath;

    public TenantOAuth2RequestWrapper(HttpServletRequest request, String slug) {
        super(request);
        String prefix = "/t/" + slug;
        String originalUri = request.getRequestURI();
        this.strippedUri = originalUri.startsWith(prefix)
            ? originalUri.substring(prefix.length())
            : originalUri;
        String originalServletPath = request.getServletPath();
        this.strippedServletPath = originalServletPath.startsWith(prefix)
            ? originalServletPath.substring(prefix.length())
            : originalServletPath;
    }

    @Override
    public String getRequestURI() {
        return strippedUri;
    }

    @Override
    public String getServletPath() {
        return strippedServletPath;
    }

    @Override
    public @Nullable String getPathInfo() {
        return null;
    }
}

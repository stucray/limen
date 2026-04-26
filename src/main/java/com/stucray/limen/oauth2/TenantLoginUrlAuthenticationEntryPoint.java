package com.stucray.limen.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

public class TenantLoginUrlAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

    public TenantLoginUrlAuthenticationEntryPoint() {
        super("/login");
    }

    @Override
    protected String determineUrlToUseForThisRequest(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) {
        String slug = TenantContext.getSlug();
        return slug != null ? "/t/" + slug + "/login" : "/login";
    }
}

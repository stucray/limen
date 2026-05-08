package com.stucray.limen.management.web;

import com.stucray.limen.useradmin.PasswordChangeRequiredInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ManagementWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Cross-tenant URL access is enforced by the TenantAccessFilter inside
        // each security chain (force-logout + redirect). The interceptor is no
        // longer needed.
        registry.addInterceptor(new PasswordChangeRequiredInterceptor())
            .addPathPatterns("/manage/t/**");
    }
}

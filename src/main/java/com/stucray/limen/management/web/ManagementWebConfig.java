package com.stucray.limen.management.web;

import com.stucray.limen.useradmin.PasswordChangeRequiredInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class ManagementWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PasswordChangeRequiredInterceptor())
            .addPathPatterns("/manage/t/**");
    }
}

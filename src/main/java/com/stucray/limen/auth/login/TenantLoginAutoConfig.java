package com.stucray.limen.auth.login;

import com.stucray.limen.auth.TenantPersistentTokenBasedRememberMeServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Assembles the {@link TenantLogin} deep module and the two default
 * {@link TenantUrlScheme} beans (OAuth2 end-user surface and management surface).
 *
 * User-supplied {@link PostLoginIntent} beans are collected via
 * {@link ObjectProvider#orderedStream()} and prepended to the three default
 * intents, so {@code @Order} on a user bean places it ahead of the defaults.
 */
@Configuration(proxyBeanMethods = false)
public class TenantLoginAutoConfig {

    @Bean
    public TenantUrlScheme oauth2UrlScheme() {
        return new TenantUrlScheme(
            "oauth2",
            HttpMethod.POST,
            "/t/*/login",
            Pattern.compile("^/t/([^/]+)(?:/.*)?$"),
            "/t/{slug}/login",
            "/t/{slug}/",
            "/t/{slug}/change-password"
        );
    }

    @Bean
    public TenantUrlScheme managementUrlScheme() {
        return new TenantUrlScheme(
            "management",
            HttpMethod.POST,
            "/manage/t/*/login",
            Pattern.compile("^/manage/t/([^/]+)(?:/.*)?$"),
            "/manage/t/{slug}/login",
            "/manage/t/{slug}/",
            "/manage/t/{slug}/change-password"
        );
    }

    @Bean
    public TenantLogin tenantLogin(
        AuthenticationManager authenticationManager,
        TenantPersistentTokenBasedRememberMeServices rememberMeServices,
        @Value("${limen.security.remember-me-key}") String rememberMeKey,
        ObjectProvider<PostLoginIntent> userIntents,
        List<TenantUrlScheme> allSchemes
    ) {
        List<PostLoginIntent> intents = new ArrayList<>(userIntents.orderedStream().toList());
        intents.add(PostLoginIntents.passwordChangeRequired());
        intents.add(PostLoginIntents.resumeOAuth2Authorize());
        intents.add(PostLoginIntents.tenantHome());
        return new TenantLogin(authenticationManager, rememberMeServices, rememberMeKey, intents, allSchemes);
    }
}

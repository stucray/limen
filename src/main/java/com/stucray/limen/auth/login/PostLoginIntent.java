package com.stucray.limen.auth.login;

import com.stucray.limen.user.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * Pluggable post-authentication policy. Each intent inspects the just-authenticated
 * principal and returns either a redirect URL to dispatch the user to, or
 * {@code null} to fall through to the next intent.
 *
 * Intents are ordered. The first non-null result wins; the chain always terminates
 * because the default chain ends with a terminal intent that always returns a URL.
 *
 * Add a new policy by registering an additional {@code @Bean PostLoginIntent} with
 * an {@code @Order} value; it slots into every registered login surface uniformly.
 */
@FunctionalInterface
public interface PostLoginIntent {

    @Nullable String resolve(
        HttpServletRequest request,
        HttpServletResponse response,
        TenantUserDetails principal,
        TenantUrlScheme scheme
    );
}

package com.stucray.limen.auth;

import com.stucray.limen.user.TenantUserDetails;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Jackson mixin enabling JSON round-tripping of {@link TenantAuthToken}.
 * Spring Authorization Server serialises the full Authentication object on
 * the OAuth2Authorization attributes map; the inherited
 * UsernamePasswordAuthenticationToken fields are handled by Spring Security's
 * own modules — this mixin adds the tenantSlug field plus a creator that
 * reconstructs the authenticated form (principal + authorities).
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public abstract class TenantAuthTokenMixin {

    @JsonCreator
    TenantAuthTokenMixin(
        @JsonProperty("tenantSlug") String tenantSlug,
        @JsonProperty("principal") TenantUserDetails principal,
        @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities
    ) {
    }
}

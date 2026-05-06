package com.stucray.limen.auth;

import com.stucray.limen.user.TenantUserDetails;

import org.springframework.security.jackson.SecurityJacksonModules;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Builds the JsonMapper used by Spring Authorization Server's
 * JdbcOAuth2AuthorizationService for serialising/deserialising the
 * OAuth2Authorization payload — including the principal, which in this
 * codebase is a {@link TenantUserDetails}.
 *
 * Spring Security's modules activate default typing with their own
 * polymorphic-type validator; the documented extension point is the
 * {@link SecurityJacksonModules#getModules(ClassLoader, BasicPolymorphicTypeValidator.Builder)}
 * overload, which lets us prepend our own allow-rules so {@code TenantUserDetails}
 * and {@code TenantAuthToken} round-trip cleanly.
 */
public final class SasJsonMapperFactory {

    private SasJsonMapperFactory() {}

    public static JsonMapper create() {
        ClassLoader cl = SasJsonMapperFactory.class.getClassLoader();
        BasicPolymorphicTypeValidator.Builder validatorBuilder = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(TenantUserDetails.class)
            .allowIfSubType(TenantAuthToken.class)
            .allowIfSubType("com.stucray.limen.");
        return JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(cl, validatorBuilder))
            .addMixIn(TenantUserDetails.class, TenantUserDetailsMixin.class)
            .addMixIn(TenantAuthToken.class, TenantAuthTokenMixin.class)
            .build();
    }
}

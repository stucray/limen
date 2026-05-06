package com.stucray.limen.auth;

import com.stucray.limen.user.TenantUserDetails;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.user.User;

/**
 * Jackson mixin enabling JSON round-tripping of {@link TenantUserDetails} as
 * stored on the OAuth2Authorization principal attribute by Spring Authorization
 * Server. Read fields directly (the record-style accessors don't follow JavaBean
 * conventions) and reconstruct via the canonical constructor.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public abstract class TenantUserDetailsMixin {

    @JsonCreator
    TenantUserDetailsMixin(@JsonProperty("user") User user, @JsonProperty("tenant") Tenant tenant) {
    }
}

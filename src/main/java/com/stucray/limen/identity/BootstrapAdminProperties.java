package com.stucray.limen.identity;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("limen.bootstrap.admin")
@Validated
public record BootstrapAdminProperties(String username, String password) {

    @AssertTrue(message = "limen.bootstrap.admin.username and password must both be set or both be unset")
    public boolean isPairConsistent() {
        boolean userSet = username != null && !username.isBlank();
        boolean passSet = password != null && !password.isBlank();
        return userSet == passSet;
    }

    public boolean isConfigured() {
        return username != null && !username.isBlank()
            && password != null && !password.isBlank();
    }
}

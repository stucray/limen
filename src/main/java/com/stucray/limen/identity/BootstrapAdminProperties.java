package com.stucray.limen.identity;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("limen.bootstrap.admin")
@Validated
public record BootstrapAdminProperties(String email, String password) {

    @AssertTrue(message = "limen.bootstrap.admin.email and password must both be set or both be unset")
    public boolean isPairConsistent() {
        boolean emailSet = email != null && !email.isBlank();
        boolean passSet = password != null && !password.isBlank();
        return emailSet == passSet;
    }

    public boolean isConfigured() {
        return email != null && !email.isBlank()
            && password != null && !password.isBlank();
    }
}

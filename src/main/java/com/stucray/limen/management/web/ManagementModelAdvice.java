package com.stucray.limen.management.web;

import com.stucray.limen.auth.TenantUserDetails;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.stucray.limen.management")
public class ManagementModelAdvice {

    @ModelAttribute("currentEmail")
    public @Nullable String currentEmail(@AuthenticationPrincipal @Nullable TenantUserDetails principal) {
        return principal == null ? null : principal.displayEmail();
    }
}

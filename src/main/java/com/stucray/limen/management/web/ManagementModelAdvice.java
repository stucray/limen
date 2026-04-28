package com.stucray.limen.management.web;

import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.stucray.limen.management")
public class ManagementModelAdvice {

    @ModelAttribute("currentUsername")
    public String currentUsername(@AuthenticationPrincipal TenantUserDetails principal) {
        return principal == null ? null : principal.displayUsername();
    }
}

package com.stucray.limen.management.users;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.auth.login.TenantPasswordChangeFlow;
import com.stucray.limen.auth.login.TenantUrlScheme;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordChangeController {

    private final TenantPasswordChangeFlow flow;
    private final TenantUrlScheme scheme;

    public PasswordChangeController(
        TenantPasswordChangeFlow flow,
        @Qualifier("managementUrlScheme") TenantUrlScheme managementUrlScheme
    ) {
        this.flow = flow;
        this.scheme = managementUrlScheme;
    }

    @GetMapping("/manage/t/{slug}/change-password")
    public String changePasswordForm(@PathVariable String slug, Model model) {
        model.addAttribute("slug", slug);
        return "manage/users/change-password";
    }

    @PostMapping("/manage/t/{slug}/change-password")
    public String changePassword(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String newPassword,
        @RequestParam String confirmPassword,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        String error = flow.validate(newPassword, confirmPassword);
        if (error != null) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", error);
            return "manage/users/change-password";
        }
        return "redirect:" + flow.changeAndRedirect(principal, scheme, newPassword, request, response);
    }
}

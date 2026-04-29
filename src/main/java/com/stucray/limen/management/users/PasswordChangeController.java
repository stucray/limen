package com.stucray.limen.management.users;

import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordChangeController {

    private final UserManagementService userManagementService;

    public PasswordChangeController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
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
        Model model
    ) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", "Passwords do not match");
            return "manage/users/change-password";
        }
        if (newPassword.isBlank()) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", "Password is required");
            return "manage/users/change-password";
        }
        userManagementService.changePassword(principal.userId(), principal.tenantId(), newPassword);
        return "redirect:/manage/t/" + slug + "/";
    }
}

package com.stucray.limen.management.users;

import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/manage/t/{slug}/users")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("users", userManagementService.listUsers(principal.tenantId()));
        return "manage/users/list";
    }

    @GetMapping("/new")
    public String newUserForm(@PathVariable String slug, Model model) {
        model.addAttribute("slug", slug);
        return "manage/users/new";
    }

    @PostMapping
    public String createUser(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String username,
        @RequestParam String temporaryPassword,
        Model model
    ) {
        try {
            userManagementService.createUser(principal.tenantId(), username, temporaryPassword);
            return "redirect:/manage/t/" + slug + "/users";
        } catch (IllegalArgumentException e) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("username", username);
            return "manage/users/new";
        }
    }

    @PostMapping("/{userId}/enable")
    public String enable(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userManagementService.setEnabled(userId, principal.tenantId(), true);
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/disable")
    public String disable(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userManagementService.setEnabled(userId, principal.tenantId(), false);
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/delete")
    public String delete(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userManagementService.deleteUser(userId, principal.tenantId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    @GetMapping("/{userId}/reset-password")
    public String resetPasswordForm(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("user", userManagementService.getUser(userId, principal.tenantId()));
        return "manage/users/reset-password";
    }

    @PostMapping("/{userId}/reset-password")
    public String resetPassword(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String temporaryPassword
    ) {
        userManagementService.resetPassword(userId, principal.tenantId(), temporaryPassword);
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/grant-owner")
    public String grantOwner(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userManagementService.setTenantOwner(userId, principal.tenantId(), true);
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/revoke-owner")
    public String revokeOwner(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userManagementService.setTenantOwner(userId, principal.tenantId(), false);
        return "redirect:/manage/t/" + slug + "/users";
    }
}

package com.stucray.limen.users;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.memberships.UserMembershipPortfolioQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/manage/t/{slug}/users")
public class UserManagementController {

    private final UserAdministrationService userAdministration;
    private final UserMembershipPortfolioQuery userMembershipPortfolioQuery;

    public UserManagementController(
        UserAdministrationService userAdministration,
        UserMembershipPortfolioQuery userMembershipPortfolioQuery
    ) {
        this.userAdministration = userAdministration;
        this.userMembershipPortfolioQuery = userMembershipPortfolioQuery;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("users", userAdministration.listUsers(principal.tenantId()));
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
        @RequestParam String email,
        @RequestParam String temporaryPassword,
        Model model
    ) {
        try {
            userAdministration.createUser(principal.tenantId(), principal.userId(), email, temporaryPassword);
            return "redirect:/manage/t/" + slug + "/users";
        } catch (IllegalArgumentException e) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("email", email);
            return "manage/users/new";
        }
    }

    @PostMapping("/{userId}/enable")
    public String enable(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.enable(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/disable")
    public String disable(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.disable(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/delete")
    public String delete(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.deleteUser(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    @GetMapping("/{userId}")
    public String detail(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("user", userAdministration.getUser(userId, principal.tenantId()));
        model.addAttribute("appMemberships", userMembershipPortfolioQuery.portfolioFor(userId, principal.tenantId()));
        return "manage/users/detail";
    }

    @GetMapping("/{userId}/reset-password")
    public String resetPasswordForm(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("user", userAdministration.getUser(userId, principal.tenantId()));
        return "manage/users/reset-password";
    }

    @PostMapping("/{userId}/reset-password")
    public String resetPassword(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String temporaryPassword
    ) {
        userAdministration.resetPassword(userId, principal.tenantId(), principal.userId(), temporaryPassword);
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/unlock")
    public String unlock(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.unlockAccount(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users/" + userId;
    }

    @PostMapping("/{userId}/grant-owner")
    public String grantOwner(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.grantTenantOwnership(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    @PostMapping("/{userId}/revoke-owner")
    public String revokeOwner(
        @PathVariable String slug,
        @PathVariable Long userId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        userAdministration.revokeTenantOwnership(userId, principal.tenantId(), principal.userId());
        return "redirect:/manage/t/" + slug + "/users";
    }

    /**
     * Single catch-all for invariant violations from {@link UserAdministrationService}.
     * Flash-redirects to the users list with the human-readable message rendered as
     * {@code errorMessage} on the next request. Sealed exception means a future
     * handler can pattern-match the four kinds (e.g. for HTTP-status discrimination)
     * with compile-time exhaustiveness.
     */
    @ExceptionHandler(UserAdminException.class)
    public String onUserAdminFailure(
        UserAdminException ex,
        HttpServletRequest request,
        RedirectAttributes flash
    ) {
        // @PathVariable on @ExceptionHandler args is not resolved reliably in
        // this Spring MVC version, so read the slug straight from the request's
        // URI-template-variables attribute (set by RequestMappingHandlerMapping).
        @SuppressWarnings("unchecked")
        Map<String, String> uriVars = (Map<String, String>)
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String slug = uriVars == null ? "" : uriVars.getOrDefault("slug", "");
        flash.addFlashAttribute("errorMessage", ex.userMessage);
        return "redirect:/manage/t/" + slug + "/users";
    }
}

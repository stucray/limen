package com.stucray.limen.oauth2;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.management.users.UserManagementService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;

@Controller
public class EndUserPasswordChangeController {

    private final UserManagementService userManagementService;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    public EndUserPasswordChangeController(
        UserManagementService userManagementService,
        UserRepository userRepository,
        TenantRepository tenantRepository
    ) {
        this.userManagementService = userManagementService;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/t/{slug}/change-password")
    public String form(@PathVariable String slug, Model model) {
        model.addAttribute("slug", slug);
        return "change-password";
    }

    @PostMapping("/t/{slug}/change-password")
    public String submit(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String newPassword,
        @RequestParam String confirmPassword,
        HttpServletRequest request,
        HttpServletResponse response,
        Model model
    ) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", "Passwords do not match");
            return "change-password";
        }
        if (newPassword.isBlank()) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", "Password is required");
            return "change-password";
        }

        Long tenantId = tenantRepository.findBySlug(slug)
            .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + slug))
            .id();
        User user = userRepository.findByUsernameAndTenantId(principal.displayUsername(), tenantId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        userManagementService.changePassword(user.id(), tenantId, newPassword);

        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null && savedRequest.getRedirectUrl().contains("/oauth2/authorize")) {
            requestCache.removeRequest(request, response);
            URI uri = URI.create(savedRequest.getRedirectUrl());
            String newPath = "/t/" + slug + uri.getRawPath();
            String query = uri.getRawQuery();
            return "redirect:" + uri.getScheme() + "://" + uri.getAuthority() + newPath
                + (query != null ? "?" + query : "");
        }
        return "redirect:/t/" + slug + "/";
    }
}

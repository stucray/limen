package com.stucray.limen.auth.login;

import com.stucray.limen.user.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Single change-password controller for both surfaces — management
 * (/manage/t/{slug}/change-password) and OAuth2 end-user
 * (/t/{slug}/change-password). Substantive work lives in
 * {@link TenantPasswordChangeFlow}; this class is HTTP plumbing only.
 *
 * <p>Per-surface dispatch uses {@code TenantUrlScheme.slugFrom(uri)} on the
 * collection-injected {@code List<TenantUrlScheme>} so the same regex that
 * matches login forms picks the right scheme here. Adding a third surface is
 * a one-line addition to the {@code @GetMapping}/{@code @PostMapping} URL
 * arrays plus a new {@code TenantUrlScheme} bean — no per-surface controller.
 */
@Controller
class PasswordChangeController {

    private static final String MGMT_PATH = "/manage/t/{slug}/change-password";
    private static final String OAUTH2_PATH = "/t/{slug}/change-password";

    private final TenantPasswordChangeFlow flow;
    private final List<TenantUrlScheme> schemes;

    PasswordChangeController(TenantPasswordChangeFlow flow, List<TenantUrlScheme> schemes) {
        this.flow = flow;
        this.schemes = schemes;
    }

    @GetMapping({MGMT_PATH, OAUTH2_PATH})
    String form(@PathVariable String slug, HttpServletRequest request, Model model) {
        model.addAttribute("slug", slug);
        return viewFor(request);
    }

    @PostMapping({MGMT_PATH, OAUTH2_PATH})
    String submit(
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
            return viewFor(request);
        }
        return "redirect:" + flow.changeAndRedirect(
            principal, schemeFor(request), newPassword, request, response);
    }

    private TenantUrlScheme schemeFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return schemes.stream()
            .filter(s -> s.slugFrom(uri) != null)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No TenantUrlScheme matches " + uri));
    }

    private String viewFor(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/manage/")
            ? "manage/users/change-password"
            : "change-password";
    }
}

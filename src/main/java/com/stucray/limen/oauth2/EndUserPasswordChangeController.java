package com.stucray.limen.oauth2;

import com.stucray.limen.user.TenantUserDetails;
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
public class EndUserPasswordChangeController {

    private final TenantPasswordChangeFlow flow;
    private final TenantUrlScheme scheme;

    public EndUserPasswordChangeController(
        TenantPasswordChangeFlow flow,
        @Qualifier("oauth2UrlScheme") TenantUrlScheme oauth2UrlScheme
    ) {
        this.flow = flow;
        this.scheme = oauth2UrlScheme;
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
        String error = flow.validate(newPassword, confirmPassword);
        if (error != null) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", error);
            return "change-password";
        }
        return "redirect:" + flow.changeAndRedirect(principal, scheme, newPassword, request, response);
    }
}

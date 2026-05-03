package com.stucray.limen.auth.ott;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/**
 * GET renders the email-collection form; POST dispatches to
 * {@link PasswordResetService#requestReset} and redirects to the shared
 * check-inbox landing with {@code flow=password-reset} so the page text
 * mentions a reset link rather than verification.
 *
 * <p>Both verbs are anonymous. The POST response is identical for known and
 * unknown emails — the same redirect target, the same query parameters — so
 * the form is not a user-existence oracle (PRD #120 user story 14).
 */
@Controller
public class ForgotPasswordController {

    private final TenantRepository tenantRepository;
    private final PasswordResetService passwordResetService;

    public ForgotPasswordController(
        TenantRepository tenantRepository,
        PasswordResetService passwordResetService
    ) {
        this.tenantRepository = tenantRepository;
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/t/{slug}/forgot-password")
    public String form(
        @PathVariable String slug,
        @RequestParam(required = false) String email,
        Model model
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        model.addAttribute("email", email == null ? "" : email);
        return "forgot-password";
    }

    @PostMapping("/t/{slug}/forgot-password")
    public String submit(
        @PathVariable String slug,
        @RequestParam String email
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/";
        }
        passwordResetService.requestReset(tenant, email);
        return "redirect:" + UriComponentsBuilder
            .fromPath("/t/" + slug + "/check-inbox")
            .queryParam("email", email)
            .queryParam("flow", "password-reset")
            .build()
            .encode(StandardCharsets.UTF_8)
            .toUriString();
    }
}

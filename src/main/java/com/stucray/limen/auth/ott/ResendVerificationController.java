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
 * GET renders the form (pre-filling the email if present in the query); POST
 * dispatches to {@link OttDispatcher#issue(OttIntent, Tenant, String)} with
 * {@link OttIntent#VERIFY_EMAIL} and redirects to
 * {@code /t/{slug}/check-inbox?email=...}.
 *
 * <p>Both verbs are anonymous — a user who just signed up has no session yet
 * and so cannot authenticate to ask for a resend. The dispatcher silently
 * no-ops on an unknown email, preserving the user-existence-oracle defence
 * laid out in PRD #120 story 14.
 */
@Controller
class ResendVerificationController {

    private final TenantRepository tenantRepository;
    private final OttDispatcher ottDispatcher;

    ResendVerificationController(
        TenantRepository tenantRepository,
        OttDispatcher ottDispatcher
    ) {
        this.tenantRepository = tenantRepository;
        this.ottDispatcher = ottDispatcher;
    }

    @GetMapping("/t/{slug}/resend-verification")
    String form(
        @PathVariable String slug,
        @RequestParam(required = false) String email,
        Model model
    ) {
        if (tenantRepository.findBySlug(slug).isEmpty()) {
            return "redirect:/";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("email", email == null ? "" : email);
        return "resend-verification";
    }

    @PostMapping("/t/{slug}/resend-verification")
    String submit(
        @PathVariable String slug,
        @RequestParam String email
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/";
        }
        ottDispatcher.issue(OttIntent.VERIFY_EMAIL, tenant, email);
        return "redirect:" + UriComponentsBuilder
            .fromPath("/t/" + slug + "/check-inbox")
            .queryParam("email", email)
            .build()
            .encode(StandardCharsets.UTF_8)
            .toUriString();
    }
}

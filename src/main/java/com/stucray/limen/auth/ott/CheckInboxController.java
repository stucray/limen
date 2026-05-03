package com.stucray.limen.auth.ott;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "we just sent you a verification email" landing page shown after signup
 * and after the resend form. The {@code email} query parameter is informational
 * only — used to render "we sent a link to {{email}}" — and does not change
 * server behaviour, so a user reloading the page does not retrigger sending.
 */
@Controller
public class CheckInboxController {

    private final TenantRepository tenantRepository;

    public CheckInboxController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/t/{slug}/check-inbox")
    public String checkInbox(
        @PathVariable String slug,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String flow,
        Model model
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        model.addAttribute("email", email == null ? "" : email);
        // Two flows land on this page: verify-email (default) and password-reset.
        // The template branches on this so the copy mentions the right link
        // and the resend prompt only renders for verify-email (resending is
        // implicit for forgot-password — the user just resubmits the form).
        model.addAttribute("flow", "password-reset".equals(flow) ? "password-reset" : "verify-email");
        return "check-inbox";
    }
}

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
        Model model
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        model.addAttribute("email", email == null ? "" : email);
        return "check-inbox";
    }
}

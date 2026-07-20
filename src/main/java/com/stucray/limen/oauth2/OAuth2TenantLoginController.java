package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class OAuth2TenantLoginController {

    private final TenantRepository tenantRepository;

    OAuth2TenantLoginController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/t/{slug}/login")
    String loginForm(
        @PathVariable String slug,
        @RequestParam(required = false) @Nullable String ref,
        Model model
    ) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/manage/t/system/login";
        }
        model.addAttribute("tenantSlug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        // Opaque reference to a durably-stashed /oauth2/authorize request (#327);
        // carried through the login POST as a hidden field so resume can replay
        // it after session eviction. Absent for ordinary (non-authorize) logins.
        model.addAttribute("authorizeRef", ref);
        return "login";
    }
}

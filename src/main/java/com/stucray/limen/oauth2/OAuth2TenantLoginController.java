package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
class OAuth2TenantLoginController {

    private final TenantRepository tenantRepository;

    public OAuth2TenantLoginController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/t/{slug}/login")
    public String loginForm(@PathVariable String slug, Model model) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/manage/t/system/login";
        }
        model.addAttribute("tenantSlug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        return "login";
    }
}

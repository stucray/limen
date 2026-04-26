package com.stucray.limen.management.web;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ManagementLoginController {

    private final TenantRepository tenantRepository;

    public ManagementLoginController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/manage/t/{slug}/login")
    public String loginPage(@PathVariable String slug, Model model) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return "redirect:/signup";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("tenantName", tenant.displayName());
        return "manage/login";
    }
}

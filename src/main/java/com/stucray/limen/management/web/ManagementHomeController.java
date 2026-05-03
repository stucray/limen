package com.stucray.limen.management.web;

import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ManagementHomeController {

    @GetMapping("/manage/t/{slug}/")
    public String home(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("tenantName", principal.tenant().displayName());
        model.addAttribute("email", principal.displayEmail());
        model.addAttribute("isSystemAdmin", principal.tenant().isSystem());
        return "manage/home";
    }
}

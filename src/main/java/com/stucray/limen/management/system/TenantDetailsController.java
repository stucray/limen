package com.stucray.limen.management.system;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/manage/t/{slug}/settings")
public class TenantDetailsController {

    private final TenantRepository tenantRepository;

    public TenantDetailsController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public String settings(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("tenant", principal.tenant());
        return "manage/settings";
    }

    @PostMapping("/display-name")
    public String updateDisplayName(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String displayName
    ) {
        tenantRepository.save(principal.tenant().withDisplayName(displayName));
        return "redirect:/manage/t/" + slug + "/settings";
    }
}

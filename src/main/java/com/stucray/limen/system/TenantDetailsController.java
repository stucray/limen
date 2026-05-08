package com.stucray.limen.system;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.tenant.TenantRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/manage/t/{slug}/settings")
class TenantDetailsController {

    private final TenantRepository tenantRepository;

    TenantDetailsController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    String settings(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("tenant", principal.tenant());
        return "manage/settings";
    }

    @PostMapping("/display-name")
    String updateDisplayName(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String displayName
    ) {
        tenantRepository.save(principal.tenant().withDisplayName(displayName));
        return "redirect:/manage/t/" + slug + "/settings";
    }
}

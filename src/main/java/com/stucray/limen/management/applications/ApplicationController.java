package com.stucray.limen.management.applications;

import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/manage/t/{slug}/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("applications", applicationService.listApplications(principal.tenantId()));
        return "manage/applications/list";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable String slug, Model model) {
        model.addAttribute("slug", slug);
        return "manage/applications/new";
    }

    @PostMapping
    public String create(
        @PathVariable String slug,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String name,
        @RequestParam(required = false) String description,
        Model model
    ) {
        try {
            applicationService.createApplication(principal.tenantId(), name, description);
            return "redirect:/manage/t/" + slug + "/applications";
        } catch (IllegalArgumentException e) {
            model.addAttribute("slug", slug);
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("name", name);
            model.addAttribute("description", description);
            return "manage/applications/new";
        }
    }

    @GetMapping("/{appId}/edit")
    public String editForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("app", applicationService.getApplication(appId, principal.tenantId()));
        return "manage/applications/edit";
    }

    @PostMapping("/{appId}/edit")
    public String update(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String name,
        @RequestParam(required = false) String description
    ) {
        applicationService.updateApplication(appId, principal.tenantId(), name, description);
        return "redirect:/manage/t/" + slug + "/applications";
    }

    @PostMapping("/{appId}/delete")
    public String delete(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        try {
            applicationService.deleteApplication(appId, principal.tenantId());
            return "redirect:/manage/t/" + slug + "/applications";
        } catch (IllegalStateException e) {
            model.addAttribute("slug", slug);
            model.addAttribute("applications", applicationService.listApplications(principal.tenantId()));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/applications/list";
        }
    }
}

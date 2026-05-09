package com.stucray.limen.roles;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/manage/t/{slug}/applications/{appId}/roles")
class RolesController {

    private final RoleManagementService roleManagementService;
    private final ApplicationService applicationService;

    RolesController(RoleManagementService roleManagementService, ApplicationService applicationService) {
        this.roleManagementService = roleManagementService;
        this.applicationService = applicationService;
    }

    @GetMapping
    String list(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("roles", roleManagementService.listRoles(appId, principal.tenantId()));
        return "manage/applications/roles/list";
    }

    @GetMapping("/new")
    String newForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        return "manage/applications/roles/new";
    }

    @PostMapping
    String create(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String name,
        @RequestParam(required = false) String description,
        Model model
    ) {
        try {
            roleManagementService.createRole(appId, principal.tenantId(), name, description);
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/roles";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("name", name);
            model.addAttribute("description", description);
            return "manage/applications/roles/new";
        }
    }

    @GetMapping("/{roleId}/edit")
    String editForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long roleId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("role", roleManagementService.getRole(roleId, appId, principal.tenantId()));
        return "manage/applications/roles/edit";
    }

    @PostMapping("/{roleId}/edit")
    String update(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long roleId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String name,
        @RequestParam(required = false) String description,
        Model model
    ) {
        try {
            roleManagementService.updateRole(roleId, appId, principal.tenantId(), name, description);
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/roles";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("role", roleManagementService.getRole(roleId, appId, principal.tenantId()));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/applications/roles/edit";
        }
    }

    @PostMapping("/{roleId}/delete")
    String delete(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long roleId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        try {
            roleManagementService.deleteRole(roleId, appId, principal.tenantId());
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/roles";
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Once membership-role join tables exist (later slices), the FK
            // ON DELETE RESTRICT will surface here. Re-render the list so the
            // admin can see which assignments to clear first.
            Application app = applicationService.getApplication(appId, principal.tenantId());
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("roles", roleManagementService.listRoles(appId, principal.tenantId()));
            model.addAttribute("errorMessage", "Cannot delete a role that is still assigned");
            return "manage/applications/roles/list";
        }
    }
}

package com.stucray.limen.system;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.provisioning.TenantProvisioner;
import com.stucray.limen.provisioning.TenantProvisioner.NewTenantRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/manage/system")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class SystemAdminController {

    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final TenantProvisioner tenantProvisioner;

    SystemAdminController(
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        TenantProvisioner tenantProvisioner
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantProvisioner = tenantProvisioner;
    }

    @GetMapping("/tenants")
    String listTenants(Model model) {
        model.addAttribute("tenants", tenantRepository.findAll());
        return "manage/system/tenants";
    }

    @GetMapping("/tenants/new")
    String newTenantForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new TenantCreateForm("", "", ""));
        }
        return "manage/system/tenant-new";
    }

    @PostMapping("/tenants/new")
    String createTenant(
        @RequestParam String slug,
        @RequestParam String displayName,
        @RequestParam String ownerEmail,
        Model model,
        RedirectAttributes redirectAttributes
    ) {
        return switch (tenantProvisioner.provision(
            NewTenantRequest.fromSystemAdminForm(slug, displayName, ownerEmail))) {
            case TenantProvisioner.Result.Provisioned p -> {
                redirectAttributes.addFlashAttribute("successMessage",
                    "Created tenant " + p.tenant().slug() + ". A verification email has been sent to "
                        + p.ownerEmail() + ".");
                yield "redirect:/manage/system/tenants";
            }
            case TenantProvisioner.Result.Rejected r -> {
                model.addAttribute("form", new TenantCreateForm(
                    trim(slug), trim(displayName), trim(ownerEmail)));
                model.addAttribute("errorField", r.field());
                model.addAttribute("errorMessage", r.message());
                yield "manage/system/tenant-new";
            }
        };
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    String suspendTenant(
        @PathVariable Long tenantId,
        @AuthenticationPrincipal TenantUserDetails principal,
        RedirectAttributes redirectAttributes
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (tenant.isSystem()) {
            redirectAttributes.addFlashAttribute("errorMessage", "The system tenant cannot be suspended");
            return "redirect:/manage/system/tenants";
        }
        tenantProvisioningService.suspend(tenant, principal.userId());
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/unsuspend")
    String unsuspendTenant(
        @PathVariable Long tenantId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        tenantProvisioningService.unsuspend(tenant, principal.userId());
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/delete")
    String deleteTenant(
        @PathVariable Long tenantId,
        @AuthenticationPrincipal TenantUserDetails principal,
        RedirectAttributes redirectAttributes
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (tenant.isSystem()) {
            redirectAttributes.addFlashAttribute("errorMessage", "The system tenant cannot be deleted");
            return "redirect:/manage/system/tenants";
        }
        // Cascade handled by FK ON DELETE CASCADE on users, applications, etc.
        tenantProvisioningService.delete(tenant, principal.userId());
        return "redirect:/manage/system/tenants";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    record TenantCreateForm(String slug, String displayName, String ownerEmail) {}
}

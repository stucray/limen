package com.stucray.limen.management.system;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.management.signup.SignupService;
import com.stucray.limen.management.signup.SignupService.SignupResult;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantUserBootstrap;
import com.stucray.limen.tenant.TenantUserBootstrap.OwnerCredentials;
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
public class SystemAdminController {

    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final TenantUserBootstrap tenantUserBootstrap;

    public SystemAdminController(
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        TenantUserBootstrap tenantUserBootstrap
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantUserBootstrap = tenantUserBootstrap;
    }

    @GetMapping("/tenants")
    public String listTenants(Model model) {
        model.addAttribute("tenants", tenantRepository.findAll());
        return "manage/system/tenants";
    }

    @GetMapping("/tenants/new")
    public String newTenantForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new TenantCreateForm("", "", ""));
        }
        return "manage/system/tenant-new";
    }

    @PostMapping("/tenants/new")
    public String createTenant(
        @RequestParam String slug,
        @RequestParam String displayName,
        @RequestParam String ownerEmail,
        Model model,
        RedirectAttributes redirectAttributes
    ) {
        String trimmedSlug = slug == null ? "" : slug.trim();
        String trimmedName = displayName == null ? "" : displayName.trim();
        String trimmedEmail = ownerEmail == null ? "" : ownerEmail.trim();
        TenantCreateForm form = new TenantCreateForm(trimmedSlug, trimmedName, trimmedEmail);

        SignupResult.Error slugError = SignupService.validateSlug(trimmedSlug, tenantRepository);
        if (slugError != null) {
            return renderFormWithError(model, form, slugError);
        }
        if (trimmedName.isBlank()) {
            return renderFormWithError(model, form,
                new SignupResult.Error("displayName", "Display name is required"));
        }
        SignupResult.Error emailError = SignupService.validateEmail(trimmedEmail);
        if (emailError != null) {
            // Helper reports the field as "email" (what /signup uses); this form's
            // input is named "ownerEmail" so we rebind to the matching template key.
            return renderFormWithError(model, form,
                new SignupResult.Error("ownerEmail", emailError.message()));
        }

        Tenant tenant = tenantUserBootstrap.bootstrap(
            trimmedSlug, trimmedName, trimmedEmail, new OwnerCredentials.GenerateRandom());
        redirectAttributes.addFlashAttribute("successMessage",
            "Created tenant " + tenant.slug() + ". A verification email has been sent to "
                + trimmedEmail + ".");
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public String suspendTenant(
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
    public String unsuspendTenant(
        @PathVariable Long tenantId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        tenantProvisioningService.unsuspend(tenant, principal.userId());
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/delete")
    public String deleteTenant(
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

    private static String renderFormWithError(
        Model model, TenantCreateForm form, SignupResult.Error error
    ) {
        model.addAttribute("form", form);
        model.addAttribute("errorField", error.field());
        model.addAttribute("errorMessage", error.message());
        return "manage/system/tenant-new";
    }

    public record TenantCreateForm(String slug, String displayName, String ownerEmail) {}
}

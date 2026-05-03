package com.stucray.limen.management.system;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/manage/system")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminController {

    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public SystemAdminController(
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        UserRepository userRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/tenants")
    public String listTenants(Model model) {
        model.addAttribute("tenants", tenantRepository.findAll());
        return "manage/system/tenants";
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
}

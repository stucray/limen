package com.stucray.limen.management.system;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/manage/system")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public SystemAdminController(
        TenantRepository tenantRepository,
        UserRepository userRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.tenantRepository = tenantRepository;
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
        RedirectAttributes redirectAttributes
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (tenant.isSystem()) {
            redirectAttributes.addFlashAttribute("errorMessage", "The system tenant cannot be suspended");
            return "redirect:/manage/system/tenants";
        }
        tenantRepository.save(tenant.withStatus(TenantStatus.SUSPENDED));
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/unsuspend")
    public String unsuspendTenant(@PathVariable Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        tenantRepository.save(tenant.withStatus(TenantStatus.ACTIVE));
        return "redirect:/manage/system/tenants";
    }

    @PostMapping("/tenants/{tenantId}/delete")
    public String deleteTenant(
        @PathVariable Long tenantId,
        RedirectAttributes redirectAttributes
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (tenant.isSystem()) {
            redirectAttributes.addFlashAttribute("errorMessage", "The system tenant cannot be deleted");
            return "redirect:/manage/system/tenants";
        }
        // Cascade handled by FK ON DELETE CASCADE on users, applications, etc.
        tenantRepository.delete(tenant);
        return "redirect:/manage/system/tenants";
    }
}

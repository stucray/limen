package com.stucray.limen.management.memberships;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationService;
import com.stucray.limen.management.roles.Role;
import com.stucray.limen.management.roles.RoleManagementService;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/manage/t/{slug}/applications/{appId}/members")
public class MembersController {

    private final ApplicationMembershipService membershipService;
    private final ApplicationService applicationService;
    private final RoleManagementService roleManagementService;
    private final UserRepository userRepository;

    public MembersController(
        ApplicationMembershipService membershipService,
        ApplicationService applicationService,
        RoleManagementService roleManagementService,
        UserRepository userRepository
    ) {
        this.membershipService = membershipService;
        this.applicationService = applicationService;
        this.roleManagementService = roleManagementService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        List<ApplicationMembership> memberships = membershipService.listMemberships(appId, principal.tenantId());
        List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("memberships", memberships);
        model.addAttribute("usernamesById", usernamesByIdFor(memberships));
        model.addAttribute("roleNamesById", roleNamesByIdFor(allRoles));
        return "manage/applications/members/list";
    }

    @GetMapping("/new")
    public String newForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("grantableUsers", membershipService.listGrantableUsers(appId, principal.tenantId()));
        return "manage/applications/members/new";
    }

    @PostMapping
    public String create(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam Long userId,
        Model model
    ) {
        try {
            membershipService.grant(appId, principal.tenantId(), userId, principal.userId());
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/members";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("grantableUsers", membershipService.listGrantableUsers(appId, principal.tenantId()));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/applications/members/new";
        }
    }

    @GetMapping("/{membershipId}/edit")
    public String editForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        ApplicationMembership membership = membershipService.getMembership(membershipId, appId, principal.tenantId());
        List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
        User member = userRepository.findById(membership.userId())
            .orElseThrow(() -> new IllegalStateException("Membership references missing user"));
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("membership", membership);
        model.addAttribute("memberUsername", member.username());
        model.addAttribute("allRoles", allRoles);
        model.addAttribute("assignedRoleIds", membership.roleIds());
        return "manage/applications/members/edit";
    }

    @PostMapping("/{membershipId}/edit")
    public String update(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam(name = "roleIds", required = false) List<Long> roleIds,
        Model model
    ) {
        try {
            Set<Long> requested = roleIds == null ? Set.of() : new java.util.LinkedHashSet<>(roleIds);
            membershipService.updateRoles(membershipId, appId, principal.tenantId(), requested);
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/members";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            ApplicationMembership membership = membershipService.getMembership(membershipId, appId, principal.tenantId());
            List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
            User member = userRepository.findById(membership.userId())
                .orElseThrow(() -> new IllegalStateException("Membership references missing user"));
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("membership", membership);
            model.addAttribute("memberUsername", member.username());
            model.addAttribute("allRoles", allRoles);
            model.addAttribute("assignedRoleIds", roleIds == null ? Set.of() : new java.util.LinkedHashSet<>(roleIds));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/applications/members/edit";
        }
    }

    @PostMapping("/{membershipId}/delete")
    public String delete(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        membershipService.revoke(membershipId, appId, principal.tenantId());
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/members";
    }

    private Map<Long, String> usernamesByIdFor(List<ApplicationMembership> memberships) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (ApplicationMembership m : memberships) {
            userRepository.findById(m.userId()).ifPresent(u -> map.put(u.id(), u.username()));
        }
        return map;
    }

    private Map<Long, String> roleNamesByIdFor(List<Role> roles) {
        Map<Long, String> map = new HashMap<>();
        for (Role r : roles) {
            map.put(r.id(), r.name());
        }
        return map;
    }
}

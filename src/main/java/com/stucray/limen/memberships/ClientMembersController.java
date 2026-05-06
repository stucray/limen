package com.stucray.limen.memberships;

import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationService;
import com.stucray.limen.clients.ClientManagementService;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.roles.Role;
import com.stucray.limen.roles.RoleManagementService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/manage/t/{slug}/applications/{appId}/clients/{registeredClientId}/members")
public class ClientMembersController {

    private final ClientMembershipService membershipService;
    private final ApplicationService applicationService;
    private final ClientManagementService clientManagementService;
    private final RoleManagementService roleManagementService;
    private final UserRepository userRepository;

    public ClientMembersController(
        ClientMembershipService membershipService,
        ApplicationService applicationService,
        ClientManagementService clientManagementService,
        RoleManagementService roleManagementService,
        UserRepository userRepository
    ) {
        this.membershipService = membershipService;
        this.applicationService = applicationService;
        this.clientManagementService = clientManagementService;
        this.roleManagementService = roleManagementService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        TenantClient client = clientManagementService.getClient(registeredClientId, principal.tenantId());
        List<ClientMembership> memberships = membershipService.listMemberships(registeredClientId, appId, principal.tenantId());
        List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("client", client);
        model.addAttribute("memberships", memberships);
        model.addAttribute("emailsById", emailsByIdFor(memberships));
        model.addAttribute("roleNamesById", roleNamesByIdFor(allRoles));
        return "manage/clients/members/list";
    }

    @GetMapping("/new")
    public String newForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        TenantClient client = clientManagementService.getClient(registeredClientId, principal.tenantId());
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("client", client);
        model.addAttribute("grantableUsers",
            membershipService.listGrantableUsers(registeredClientId, appId, principal.tenantId()));
        return "manage/clients/members/new";
    }

    @PostMapping
    public String create(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam Long userId,
        Model model
    ) {
        try {
            membershipService.grant(registeredClientId, appId, principal.tenantId(), userId, principal.userId());
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients/" + registeredClientId + "/members";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            TenantClient client = clientManagementService.getClient(registeredClientId, principal.tenantId());
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("client", client);
            model.addAttribute("grantableUsers",
                membershipService.listGrantableUsers(registeredClientId, appId, principal.tenantId()));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/clients/members/new";
        }
    }

    @GetMapping("/{membershipId}/edit")
    public String editForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        Application app = applicationService.getApplication(appId, principal.tenantId());
        TenantClient client = clientManagementService.getClient(registeredClientId, principal.tenantId());
        ClientMembership membership = membershipService.getMembership(membershipId, registeredClientId, appId, principal.tenantId());
        List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
        User member = userRepository.findById(membership.userId())
            .orElseThrow(() -> new IllegalStateException("Membership references missing user"));
        model.addAttribute("slug", slug);
        model.addAttribute("app", app);
        model.addAttribute("client", client);
        model.addAttribute("membership", membership);
        model.addAttribute("memberEmail", member.email());
        model.addAttribute("allRoles", allRoles);
        model.addAttribute("assignedRoleIds", membership.roleIds());
        return "manage/clients/members/edit";
    }

    @PostMapping("/{membershipId}/edit")
    public String update(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam(name = "roleIds", required = false) List<Long> roleIds,
        Model model
    ) {
        try {
            Set<Long> requested = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
            membershipService.updateRoles(membershipId, registeredClientId, appId, principal.tenantId(), requested);
            return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients/" + registeredClientId + "/members";
        } catch (IllegalArgumentException e) {
            Application app = applicationService.getApplication(appId, principal.tenantId());
            TenantClient client = clientManagementService.getClient(registeredClientId, principal.tenantId());
            ClientMembership membership = membershipService.getMembership(membershipId, registeredClientId, appId, principal.tenantId());
            List<Role> allRoles = roleManagementService.listRoles(appId, principal.tenantId());
            User member = userRepository.findById(membership.userId())
                .orElseThrow(() -> new IllegalStateException("Membership references missing user"));
            model.addAttribute("slug", slug);
            model.addAttribute("app", app);
            model.addAttribute("client", client);
            model.addAttribute("membership", membership);
            model.addAttribute("memberEmail", member.email());
            model.addAttribute("allRoles", allRoles);
            model.addAttribute("assignedRoleIds", roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds));
            model.addAttribute("errorMessage", e.getMessage());
            return "manage/clients/members/edit";
        }
    }

    @PostMapping("/{membershipId}/delete")
    public String delete(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @PathVariable Long membershipId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        membershipService.revoke(membershipId, registeredClientId, appId, principal.tenantId());
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients/" + registeredClientId + "/members";
    }

    private Map<Long, String> emailsByIdFor(List<ClientMembership> memberships) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (ClientMembership m : memberships) {
            userRepository.findById(m.userId()).ifPresent(u -> map.put(u.id(), u.email()));
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

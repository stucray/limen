package com.stucray.limen.management.clients;

import com.stucray.limen.management.applications.ApplicationService;
import com.stucray.limen.auth.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/manage/t/{slug}/applications/{appId}/clients")
public class ClientManagementController {

    private final ClientManagementService clientManagementService;
    private final ApplicationService applicationService;

    public ClientManagementController(
        ClientManagementService clientManagementService,
        ApplicationService applicationService
    ) {
        this.clientManagementService = clientManagementService;
        this.applicationService = applicationService;
    }

    @GetMapping
    public String list(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("application", applicationService.getApplication(appId, principal.tenantId()));
        model.addAttribute("clients", clientManagementService.listClients(appId, principal.tenantId()));
        return "manage/clients/list";
    }

    @GetMapping("/new")
    public String newClientForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("application", applicationService.getApplication(appId, principal.tenantId()));
        return "manage/clients/new";
    }

    @PostMapping
    public String createClient(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam String displayName,
        @RequestParam(required = false) String[] grantTypes,
        @RequestParam(required = false) String redirectUris,
        @RequestParam(required = false) String postLogoutRedirectUris,
        @RequestParam(required = false) String scopes,
        @RequestParam(defaultValue = "false") boolean requirePkce,
        @RequestParam(defaultValue = "true") boolean confidential,
        @RequestParam(defaultValue = "5") long accessTokenTtlMinutes,
        @RequestParam(defaultValue = "30") long refreshTokenTtlDays,
        @RequestParam(defaultValue = "false") boolean reuseRefreshTokens,
        RedirectAttributes redirectAttributes
    ) {
        Set<AuthorizationGrantType> grants = grantTypes == null ? Set.of() :
            Arrays.stream(grantTypes)
                .map(AuthorizationGrantType::new)
                .collect(Collectors.toSet());

        Set<String> redirectUriSet = parseLines(redirectUris);
        Set<String> postLogoutSet = parseLines(postLogoutRedirectUris);
        Set<String> scopeSet = parseLines(scopes);

        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(
            appId, principal.tenantId(), displayName, grants, redirectUriSet, postLogoutSet, scopeSet,
            requirePkce, confidential, accessTokenTtlMinutes, refreshTokenTtlDays, reuseRefreshTokens
        );

        if (result.rawSecret() != null) {
            redirectAttributes.addFlashAttribute("clientSecret", result.rawSecret());
            redirectAttributes.addFlashAttribute("clientId", result.client().registeredClientId());
        }

        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    @GetMapping("/{registeredClientId}/edit")
    public String editClientForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("application", applicationService.getApplication(appId, principal.tenantId()));
        model.addAttribute("clientSettings", clientManagementService.getClientWithSettings(registeredClientId, principal.tenantId()));
        return "manage/clients/edit";
    }

    @PostMapping("/{registeredClientId}/edit")
    public String updateClient(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @RequestParam(defaultValue = "5") long accessTokenTtlMinutes,
        @RequestParam(defaultValue = "30") long refreshTokenTtlDays,
        @RequestParam(defaultValue = "false") boolean reuseRefreshTokens,
        @RequestParam(defaultValue = "false") boolean requirePkce
    ) {
        clientManagementService.updateClientSettings(
            registeredClientId, principal.tenantId(),
            accessTokenTtlMinutes, refreshTokenTtlDays, reuseRefreshTokens, requirePkce
        );
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    @PostMapping("/{registeredClientId}/delete")
    public String deleteClient(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        clientManagementService.deleteClient(registeredClientId, principal.tenantId());
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    @PostMapping("/{registeredClientId}/rotate-secret")
    public String rotateSecret(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        RedirectAttributes redirectAttributes
    ) {
        ClientManagementService.SecretRotationResult result = clientManagementService.rotateSecret(registeredClientId, principal.tenantId());
        redirectAttributes.addFlashAttribute("clientSecret", result.rawSecret());
        redirectAttributes.addFlashAttribute("clientId", registeredClientId);
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    private static Set<String> parseLines(String input) {
        if (input == null || input.isBlank()) return Set.of();
        return Arrays.stream(input.split("[\n,]+"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }
}

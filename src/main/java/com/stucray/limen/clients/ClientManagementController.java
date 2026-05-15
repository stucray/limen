package com.stucray.limen.clients;

import com.stucray.limen.applications.ApplicationService;
import com.stucray.limen.user.TenantUserDetails;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/manage/t/{slug}/applications/{appId}/clients")
class ClientManagementController {

    private final ClientManagementService clientManagementService;
    private final ApplicationService applicationService;

    ClientManagementController(
        ClientManagementService clientManagementService,
        ApplicationService applicationService
    ) {
        this.clientManagementService = clientManagementService;
        this.applicationService = applicationService;
    }

    @GetMapping
    String list(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("app", applicationService.getApplication(appId, principal.tenantId()));
        model.addAttribute("clients", clientManagementService.listClients(appId, principal.tenantId()));
        return "manage/clients/list";
    }

    @GetMapping("/new")
    String newClientForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("app", applicationService.getApplication(appId, principal.tenantId()));
        return "manage/clients/new";
    }

    @PostMapping
    String createClient(
        @PathVariable String slug,
        @PathVariable Long appId,
        @AuthenticationPrincipal TenantUserDetails principal,
        @ModelAttribute CreateClientForm form,
        RedirectAttributes redirectAttributes
    ) {
        Set<AuthorizationGrantType> grants = form.getGrantTypes().stream()
            .map(AuthorizationGrantType::new)
            .collect(Collectors.toSet());

        // displayName carried over the prior @RequestParam contract (required by
        // default). Fail loudly if a malformed POST omits it rather than threading
        // a nullable through the service.
        String displayName = Objects.requireNonNull(form.getDisplayName(), "displayName is required");
        CreateClientCommand command = new CreateClientCommand(
            appId, principal.tenantId(), displayName,
            grants,
            parseLines(form.getRedirectUris()),
            parseLines(form.getPostLogoutRedirectUris()),
            parseLines(form.getScopes()),
            form.isRequirePkce(), form.isConfidential(),
            form.getAccessTokenTtlMinutes(), form.getRefreshTokenTtlDays(),
            form.isReuseRefreshTokens()
        );

        ClientManagementService.ClientCreationResult result = clientManagementService.createClient(command);

        if (result.rawSecret() != null) {
            redirectAttributes.addFlashAttribute("clientSecret", result.rawSecret());
            redirectAttributes.addFlashAttribute("clientId", result.wireClientId());
        }

        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    @GetMapping("/{registeredClientId}/edit")
    String editClientForm(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        Model model
    ) {
        model.addAttribute("slug", slug);
        model.addAttribute("app", applicationService.getApplication(appId, principal.tenantId()));
        model.addAttribute("clientSettings", clientManagementService.getClientWithSettings(registeredClientId, principal.tenantId()));
        return "manage/clients/edit";
    }

    @PostMapping("/{registeredClientId}/edit")
    String updateClient(
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
    String deleteClient(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal
    ) {
        clientManagementService.deleteClient(registeredClientId, principal.tenantId());
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    @PostMapping("/{registeredClientId}/rotate-secret")
    String rotateSecret(
        @PathVariable String slug,
        @PathVariable Long appId,
        @PathVariable String registeredClientId,
        @AuthenticationPrincipal TenantUserDetails principal,
        RedirectAttributes redirectAttributes
    ) {
        ClientManagementService.SecretRotationResult result = clientManagementService.rotateSecret(registeredClientId, principal.tenantId(), principal.userId());
        redirectAttributes.addFlashAttribute("clientSecret", result.rawSecret());
        redirectAttributes.addFlashAttribute("clientId", result.wireClientId());
        return "redirect:/manage/t/" + slug + "/applications/" + appId + "/clients";
    }

    private static Set<String> parseLines(@Nullable String input) {
        if (input == null || input.isBlank()) return Set.of();
        return Arrays.stream(input.split("[\n,]+"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }
}

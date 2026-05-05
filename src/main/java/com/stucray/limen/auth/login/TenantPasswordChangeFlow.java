package com.stucray.limen.auth.login;

import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.PasswordResetService;
import com.stucray.limen.auth.ott.TenantOttAuthentication;
import com.stucray.limen.management.users.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Shared post-login password-change orchestration. Both surface controllers
 * (/t/{slug}/change-password and /manage/t/{slug}/change-password) call into
 * this so input validation, persistence, and saved-/oauth2/authorize-resume
 * are defined exactly once.
 *
 * The resume target is always tenant-prefixed under /t/{slug}/oauth2/authorize
 * regardless of the surface the user changed their password on, because the
 * authorize endpoint only lives on the OAuth2 surface.
 */
@Component
public class TenantPasswordChangeFlow {

    private final UserManagementService userManagementService;
    private final PasswordResetService passwordResetService;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public TenantPasswordChangeFlow(
        UserManagementService userManagementService,
        PasswordResetService passwordResetService
    ) {
        this.userManagementService = userManagementService;
        this.passwordResetService = passwordResetService;
    }

    /** Returns an error message if validation fails, else null. */
    public @Nullable String validate(String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) return "Passwords do not match";
        if (newPassword.isBlank()) return "Password is required";
        return null;
    }

    /**
     * Persist the new password and compute the post-change redirect URL:
     * the saved /oauth2/authorize request (tenant-prefixed) if present, else
     * {@code scheme.homeUrl(slug)}.
     */
    public String changeAndRedirect(
        TenantUserDetails principal,
        TenantUrlScheme scheme,
        String newPassword,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        userManagementService.changePassword(
            principal.userId(), principal.tenantId(), newPassword);

        // If the current Authentication is a TenantOttAuthentication carrying
        // PASSWORD_RESET, this submission is the tail of a reset journey. Emit
        // the reset-completed audit event and rotate the SecurityContext to a
        // plain authenticated principal — that ends the journey in code, so a
        // refresh of the form cannot re-fire completeReset and cannot keep the
        // user routed at change-password by passwordChangeAfterReset(). The
        // PasswordChangedEvent fired by changePassword above covers the
        // hash-rotation audit row; this is the additional "reset journey done"
        // marker.
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof TenantOttAuthentication tott
            && tott.intent() == OttIntent.PASSWORD_RESET) {
            passwordResetService.completeReset(principal.userId(), principal.tenantId());
            SecurityContext rotated = SecurityContextHolder.createEmptyContext();
            rotated.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(rotated);
            securityContextRepository.saveContext(rotated, request, response);
        }

        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null && saved.getRedirectUrl().contains("/oauth2/authorize")) {
            requestCache.removeRequest(request, response);
            return prependTenantPrefix(saved.getRedirectUrl(), principal.tenantSlug());
        }
        return scheme.homeUrl(principal.tenantSlug());
    }

    private static String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

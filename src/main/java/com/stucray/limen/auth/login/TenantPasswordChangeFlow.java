package com.stucray.limen.auth.login;

import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.auth.ott.OttCompletionService;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.TenantOttAuthentication;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Objects;

/**
 * Shared post-login password-change orchestration. Both surface controllers
 * (/t/{slug}/change-password and /manage/t/{slug}/change-password) call into
 * this so input validation, persistence, and saved-/oauth2/authorize-resume
 * are defined exactly once.
 *
 * <p>Self-service password change lives here (not on
 * {@code UserAdministrationService}) because the principal acts on their own
 * account: the admin-side invariants (no self-targeting, no orphaning the
 * tenant) are not meaningful here. The {@link PasswordChangedEvent.Trigger}
 * enum discriminates this self-service write from the admin-reset write,
 * which keeps the audit row precise.
 *
 * <p>The resume target is always tenant-prefixed under
 * /t/{slug}/oauth2/authorize regardless of the surface the user changed
 * their password on, because the authorize endpoint only lives on the OAuth2
 * surface.
 */
@Component
class TenantPasswordChangeFlow {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final OttCompletionService ottCompletionService;
    // Read from the dedicated SAS session attribute (see PostLoginIntents) so
    // the resume URL survives unrelated unauthenticated requests handled by
    // other chains (#285).
    private final HttpSessionRequestCache requestCache = oauth2AuthorizeRequestCache();
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private static HttpSessionRequestCache oauth2AuthorizeRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setSessionAttrName(PostLoginIntents.OAUTH2_AUTHORIZE_SAVED_REQUEST_ATTR);
        return cache;
    }

    TenantPasswordChangeFlow(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        ApplicationEventPublisher eventPublisher,
        OttCompletionService ottCompletionService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.ottCompletionService = ottCompletionService;
    }

    /** Returns an error message if validation fails, else null. */
    @Nullable String validate(String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) return "Passwords do not match";
        if (newPassword.isBlank()) return "Password is required";
        return null;
    }

    /**
     * Persist the new password and compute the post-change redirect URL:
     * the saved /oauth2/authorize request (tenant-prefixed) if present, else
     * {@code scheme.homeUrl(slug)}. {@code @Transactional} so the audit
     * listener (AFTER_COMMIT) has a transaction to hook onto.
     */
    @Transactional
    String changeAndRedirect(
        TenantUserDetails principal,
        TenantUrlScheme scheme,
        String newPassword,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        User refreshedUser = rotatePassword(principal.userId(), principal.tenantId(), newPassword);

        // The saved User has mustChangePassword=false, so build a fresh
        // TenantUserDetails and refresh the SecurityContext. Without this the
        // session keeps the stale principal (mustChangePassword=true), and
        // PasswordChangeRequiredInterceptor bounces the user back to the
        // change-password page on the next management-surface request. The
        // hash rotation in the saved User is also reflected, so downstream
        // request authentication uses the new hash.
        TenantUserDetails refreshed = new TenantUserDetails(refreshedUser, principal.tenant());

        // If the current Authentication is a TenantOttAuthentication carrying
        // PASSWORD_RESET, this submission is the tail of a reset journey. Emit
        // the reset-completed audit event and rotate the SecurityContext to a
        // plain authenticated principal — that ends the journey in code, so a
        // refresh of the form cannot re-fire completeReset and cannot keep the
        // user routed at change-password by passwordChangeAfterReset(). The
        // PasswordChangedEvent fired by rotatePassword above covers the
        // hash-rotation audit row; this is the additional "reset journey done"
        // marker.
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof TenantOttAuthentication tott
            && tott.intent() == OttIntent.PASSWORD_RESET) {
            ottCompletionService.markPasswordResetCompleted(principal.userId(), principal.tenantId());
        }

        SecurityContext rotated = SecurityContextHolder.createEmptyContext();
        rotated.setAuthentication(new UsernamePasswordAuthenticationToken(
            refreshed, null, refreshed.getAuthorities()));
        SecurityContextHolder.setContext(rotated);
        securityContextRepository.saveContext(rotated, request, response);

        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null && saved.getRedirectUrl().contains("/oauth2/authorize")) {
            requestCache.removeRequest(request, response);
            return prependTenantPrefix(saved.getRedirectUrl(), principal.tenantSlug());
        }
        return scheme.homeUrl(principal.tenantSlug());
    }

    private User rotatePassword(Long userId, Long tenantId, String newPassword) {
        User user = userRepository.findById(userId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        boolean wasForced = user.mustChangePassword();
        User saved = userRepository.save(user
            .withPasswordHash(Objects.requireNonNull(passwordEncoder.encode(newPassword)))
            .withMustChangePassword(false));
        eventPublisher.publishEvent(new PasswordChangedEvent(
            tenantId, userId,
            wasForced ? PasswordChangedEvent.Trigger.FORCED : PasswordChangedEvent.Trigger.SELF_SERVICE));
        return saved;
    }

    private static String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

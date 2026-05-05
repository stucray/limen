package com.stucray.limen.auth.login;

import com.stucray.limen.audit.events.PasswordChangedEvent;
import com.stucray.limen.auth.TenantUserDetails;
import com.stucray.limen.auth.ott.PasswordResetService;
import com.stucray.limen.auth.ott.PasswordResetSessionMarker;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class TenantPasswordChangeFlow {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordResetService passwordResetService;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    public TenantPasswordChangeFlow(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        ApplicationEventPublisher eventPublisher,
        PasswordResetService passwordResetService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
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
     * {@code scheme.homeUrl(slug)}. {@code @Transactional} so the audit
     * listener (AFTER_COMMIT) has a transaction to hook onto.
     */
    @Transactional
    public String changeAndRedirect(
        TenantUserDetails principal,
        TenantUrlScheme scheme,
        String newPassword,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        rotatePassword(principal.userId(), principal.tenantId(), newPassword);

        // If the marker is present, this submission is the tail of a
        // password-reset journey. Clear it (so a refresh of the form does not
        // double-fire completion) and emit the reset-completed audit event,
        // before computing the redirect target. The PasswordChangedEvent fired
        // by rotatePassword above covers the hash-rotation audit row; this is
        // the additional "the reset journey is done" marker.
        if (PasswordResetSessionMarker.isPresent(request)) {
            PasswordResetSessionMarker.clear(request);
            passwordResetService.completeReset(principal.userId(), principal.tenantId());
        }

        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null && saved.getRedirectUrl().contains("/oauth2/authorize")) {
            requestCache.removeRequest(request, response);
            return prependTenantPrefix(saved.getRedirectUrl(), principal.tenantSlug());
        }
        return scheme.homeUrl(principal.tenantSlug());
    }

    private void rotatePassword(Long userId, Long tenantId, String newPassword) {
        User user = userRepository.findById(userId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        boolean wasForced = user.mustChangePassword();
        userRepository.save(user
            .withPasswordHash(Objects.requireNonNull(passwordEncoder.encode(newPassword)))
            .withMustChangePassword(false));
        eventPublisher.publishEvent(new PasswordChangedEvent(
            tenantId, userId,
            wasForced ? PasswordChangedEvent.Trigger.FORCED : PasswordChangedEvent.Trigger.SELF_SERVICE));
    }

    private static String prependTenantPrefix(String redirectUrl, String slug) {
        URI uri = URI.create(redirectUrl);
        String newPath = "/t/" + slug + uri.getRawPath();
        String query = uri.getRawQuery();
        return uri.getScheme() + "://" + uri.getAuthority() + newPath
            + (query != null ? "?" + query : "");
    }
}

package com.stucray.limen.auth.ott;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Session-scoped breadcrumb that tells the post-login pipeline "the user just
 * consumed a password-reset OTT, route them through change-password before
 * anything else." Set inside {@link TenantOttAuthenticationProvider} (where the
 * intent on the consumed row is the only authoritative source for which flow
 * we're in), read by {@code PostLoginIntents.passwordChangeAfterReset}, and
 * cleared by {@code TenantPasswordChangeFlow} on successful submission.
 *
 * <p>The marker also doubles as the "completion event has been emitted" guard:
 * the flow only fires {@link PasswordResetService#completeReset} if the
 * attribute was present, so a vanilla self-service password change does not
 * masquerade as a reset.
 *
 * <p>Lives on the HTTP session because the OTT authentication step (where the
 * marker is set) runs in a different request from the change-password POST
 * (where it is read + cleared); session is the smallest scope that spans both.
 */
public final class PasswordResetSessionMarker {

    public static final String ATTRIBUTE_NAME = "limen.passwordResetRequired";

    private PasswordResetSessionMarker() {}

    /** Set the marker for the current request's session. Creates a session if one does not exist. */
    public static void setOnCurrentRequest() {
        HttpServletRequest req = currentRequestOrNull();
        if (req == null) {
            // Defence in depth: in production the OTT auth provider always runs
            // inside a request, but tests that exercise the provider directly may
            // not bind RequestContextHolder. Silently no-op so the unit test path
            // stays usable; the integration tests cover the session-bound flow.
            return;
        }
        req.getSession(true).setAttribute(ATTRIBUTE_NAME, Boolean.TRUE);
    }

    /** True iff the marker is present on the request's session. */
    public static boolean isPresent(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ATTRIBUTE_NAME));
    }

    /** Remove the marker. Safe to call when no session or no attribute exists. */
    public static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE_NAME);
        }
    }

    private static @Nullable HttpServletRequest currentRequestOrNull() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}

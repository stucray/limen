package com.stucray.limen.auth.ott;

import com.stucray.limen.email.EmailMessage;
import com.stucray.limen.email.EmailSender;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders + sends OTT magic-link emails. The class plays two roles:
 *
 * <ol>
 *   <li>It implements {@link OneTimeTokenGenerationSuccessHandler}, so that
 *       Spring's {@code GenerateOneTimeTokenFilter} (registered by the
 *       {@code oneTimeTokenLogin()} DSL) has the bean it needs. In normal
 *       operation Limen does not route any UI to {@code /ott/generate};
 *       this exists to satisfy the configurer contract.</li>
 *   <li>It exposes {@link #sendVerification(Tenant, String, String)} for
 *       direct invocation from the signup + resend-verification controllers,
 *       which already know the tenant and intent at call time and don't need
 *       a DB round-trip to look them up.</li>
 * </ol>
 *
 * <p>Magic links are tenant-scoped: {@code /t/{slug}/login/ott?token=...}.
 */
@Component
public class OttEmailNotifier implements OneTimeTokenGenerationSuccessHandler {

    private static final String LOOKUP_SQL =
        "SELECT tenant_id, intent FROM one_time_tokens WHERE token_value = ?";

    private final EmailSender emailSender;
    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public OttEmailNotifier(
        EmailSender emailSender,
        TenantRepository tenantRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.emailSender = emailSender;
        this.tenantRepository = tenantRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Direct entry point used by signup + resend-verification: build the
     * verification email and dispatch it via {@link EmailSender}.
     */
    public void sendVerification(Tenant tenant, String recipientEmail, String tokenValue) {
        send(tenant, recipientEmail, tokenValue, OttIntent.VERIFY_EMAIL);
    }

    /**
     * Direct entry point used by the forgot-password flow: build the
     * password-reset email and dispatch it via {@link EmailSender}.
     */
    public void sendPasswordReset(Tenant tenant, String recipientEmail, String tokenValue) {
        send(tenant, recipientEmail, tokenValue, OttIntent.PASSWORD_RESET);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken oneTimeToken)
            throws java.io.IOException {
        // Path used only when Spring's /ott/generate filter fires. The freshly
        // inserted row carries the intent + tenant; look them up so the right
        // template + slug are picked.
        Lookup lookup = lookupTenantAndIntent(oneTimeToken.getTokenValue());
        Tenant tenant = tenantRepository.findById(lookup.tenantId())
            .orElseThrow(() -> new IllegalStateException(
                "OTT row references missing tenant id=" + lookup.tenantId()));
        send(tenant, oneTimeToken.getUsername(), oneTimeToken.getTokenValue(), lookup.intent());
        // The default Spring handler redirects after; mirror that so a real
        // /ott/generate POST doesn't leave the browser on a blank page.
        redirectStrategy.sendRedirect(request, response, "/t/" + tenant.slug() + "/check-inbox");
    }

    private void send(Tenant tenant, String recipientEmail, String tokenValue, OttIntent intent) {
        String slug = tenant.slug();
        String link = magicLink(slug, tokenValue, intent);
        String subject = subjectFor(intent);
        String body = bodyFor(intent, tenant.displayName(), link);
        emailSender.send(new EmailMessage(recipientEmail, subject, body));
    }

    private static String magicLink(String slug, String tokenValue, OttIntent intent) {
        // Slice 4 wires both intents through the same /login/ott consume path;
        // the OTT row's intent column drives the post-consume behaviour
        // (verify the email, force password change, etc.) on the auth-provider
        // side, so the URL itself doesn't need to differ.
        return "/t/" + slug + "/login/ott?token=" + tokenValue;
    }

    private static String subjectFor(OttIntent intent) {
        return switch (intent) {
            case VERIFY_EMAIL -> "Verify your Limen email address";
            case PASSWORD_RESET -> "Reset your Limen password";
        };
    }

    private static String bodyFor(OttIntent intent, String tenantName, String link) {
        return switch (intent) {
            case VERIFY_EMAIL -> ""
                + "Welcome to " + tenantName + " on Limen.\n\n"
                + "Click the link below to verify your email address and activate your account:\n\n"
                + link + "\n\n"
                + "This link is single-use and will expire in 60 minutes.\n";
            case PASSWORD_RESET -> ""
                + "We received a password-reset request for your account at " + tenantName + ".\n\n"
                + "Click the link below to set a new password:\n\n"
                + link + "\n\n"
                + "This link is single-use and will expire in 60 minutes.\n"
                + "If you didn't request this, you can safely ignore the message.\n";
        };
    }

    private Lookup lookupTenantAndIntent(String tokenValue) {
        // requireTenantId is intentionally not called here — the bound TenantScope
        // is a function of how the request reached us, but Spring's generator
        // filter populates the row before invoking handle(), so we trust the row.
        List<Lookup> rows = jdbcTemplate.query(LOOKUP_SQL, (rs, idx) -> {
            String wire = rs.getString("intent");
            OttIntent intent = OttIntent.fromWire(wire);
            if (intent == null) {
                throw new IllegalStateException("Unknown OTT intent in storage: " + wire);
            }
            return new Lookup(rs.getLong("tenant_id"), intent);
        }, tokenValue);
        if (rows.isEmpty()) {
            // Should not happen — Spring just inserted the row before calling us.
            // Fall back to defaults rather than NPE so the email send path stays
            // recoverable if a row is, say, deleted out from under us.
            Long current = TenantScope.tenantId();
            if (current == null) {
                throw new IllegalStateException(
                    "OTT row missing for token and TenantScope is unbound; cannot dispatch email");
            }
            return new Lookup(current, OttIntent.VERIFY_EMAIL);
        }
        return rows.get(0);
    }

    private record Lookup(Long tenantId, OttIntent intent) {}
}

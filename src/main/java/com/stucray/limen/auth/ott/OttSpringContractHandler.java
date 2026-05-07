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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts the OTT module to Spring Security's
 * {@link OneTimeTokenGenerationSuccessHandler} contract. Wired into
 * {@code oneTimeTokenLogin().tokenGenerationSuccessHandler(...)} on the
 * tenant-surface filter chain.
 *
 * <p>In normal operation Limen does not route any UI to {@code /ott/generate};
 * application-side OTT issuance goes through {@link OttDispatcher}. This bean
 * exists so {@code GenerateOneTimeTokenFilter} (registered by the
 * {@code oneTimeTokenLogin()} DSL) has the bean it needs — and so a hypothetical
 * {@code /ott/generate} POST does not leave the browser on a blank page.
 *
 * <p>Kept as a separate bean from {@link OttDispatcher} because the two paths
 * are distinct concerns: the dispatcher is called by application code that
 * already knows the tenant and intent at call time; this handler is invoked
 * by the framework filter after Spring has just inserted a row, and so reads
 * the row back to find tenant + intent.
 */
@Component
class OttSpringContractHandler implements OneTimeTokenGenerationSuccessHandler {

    private static final String LOOKUP_SQL =
        "SELECT tenant_id, intent FROM one_time_tokens WHERE token_value = ?";

    private final EmailSender emailSender;
    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Map<OttIntent, OttIntentHandler> handlers;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    OttSpringContractHandler(
        EmailSender emailSender,
        TenantRepository tenantRepository,
        JdbcTemplate jdbcTemplate,
        List<OttIntentHandler> handlers
    ) {
        this.emailSender = emailSender;
        this.tenantRepository = tenantRepository;
        this.jdbcTemplate = jdbcTemplate;
        EnumMap<OttIntent, OttIntentHandler> byIntent = new EnumMap<>(OttIntent.class);
        for (OttIntentHandler handler : handlers) {
            byIntent.put(handler.intent(), handler);
        }
        this.handlers = byIntent;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken oneTimeToken)
            throws java.io.IOException {
        Lookup lookup = lookupTenantAndIntent(oneTimeToken.getTokenValue());
        Tenant tenant = tenantRepository.findById(lookup.tenantId())
            .orElseThrow(() -> new IllegalStateException(
                "OTT row references missing tenant id=" + lookup.tenantId()));
        OttIntentHandler handler = Objects.requireNonNull(handlers.get(lookup.intent()),
            () -> "No OttIntentHandler bean for intent " + lookup.intent());
        String magicLink = "/t/" + tenant.slug() + "/login/ott?token=" + oneTimeToken.getTokenValue();
        emailSender.send(new EmailMessage(
            oneTimeToken.getUsername(),
            handler.subject(tenant),
            handler.body(tenant, magicLink)));
        // The default Spring handler redirects after dispatching; mirror that
        // so a real /ott/generate POST doesn't leave the browser on a blank page.
        redirectStrategy.sendRedirect(request, response, "/t/" + tenant.slug() + "/check-inbox");
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
            // Fall back to the bound TenantScope rather than NPE so the email
            // send path stays recoverable if a row is, say, deleted out from
            // under us.
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

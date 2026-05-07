package com.stucray.limen.auth.ott;

import com.stucray.limen.email.EmailMessage;
import com.stucray.limen.email.EmailSender;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single entry point for issuing an OTT under any {@link OttIntent}. Hides the
 * uniform parts of every issue path:
 *
 * <ul>
 *   <li>The user-lookup branch (existence-oracle defence: a missing user is a
 *       silent no-op for email delivery, but a row in audit so investigators
 *       still see the attempt).</li>
 *   <li>The {@link TenantScope} binding around generate + send.</li>
 *   <li>{@link TenantAwareOneTimeTokenService#generateForIntent}.</li>
 *   <li>{@link EmailSender} dispatch with subject/body sourced from the
 *       per-intent {@link OttIntentHandler}.</li>
 *   <li>{@link ApplicationEventPublisher} emission of the handler's
 *       intent-specific issued event.</li>
 *   <li>The {@code @Transactional} boundary so AFTER_COMMIT audit listeners
 *       attach.</li>
 * </ul>
 *
 * <p>Per-intent behaviour (subject, body, audit-event shape) lives on
 * {@link OttIntentHandler} beans and is collected via Spring map-injection.
 * Adding a new intent therefore requires edits in exactly two places: a new
 * handler bean and a new audit event record — zero edits here.
 *
 * <p>Two overloads distinguish the two issue shapes:
 *
 * <ol>
 *   <li>{@link #issue(OttIntent, Tenant, User)} — caller already has the user
 *       (signup path, system-admin tenant-create); always delivers.</li>
 *   <li>{@link #issue(OttIntent, Tenant, String)} — caller only has an email
 *       (resend, forgot-password); delivers iff the address resolves.</li>
 * </ol>
 */
@Service
public class OttDispatcher {

    private final Map<OttIntent, OttIntentHandler> handlers;
    private final TenantAwareOneTimeTokenService tokenService;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final ApplicationEventPublisher eventPublisher;

    public OttDispatcher(
        List<OttIntentHandler> handlers,
        TenantAwareOneTimeTokenService tokenService,
        UserRepository userRepository,
        EmailSender emailSender,
        ApplicationEventPublisher eventPublisher
    ) {
        // Spring's collection-injection delivers every OttIntentHandler bean
        // in the context. Index by intent() so dispatch is a constant-time
        // lookup; fail fast if two handlers claim the same intent or any
        // intent has no handler.
        EnumMap<OttIntent, OttIntentHandler> byIntent = new EnumMap<>(OttIntent.class);
        for (OttIntentHandler handler : handlers) {
            OttIntentHandler previous = byIntent.put(handler.intent(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate OttIntentHandler for intent " + handler.intent()
                        + ": " + previous.getClass().getName()
                        + " and " + handler.getClass().getName());
            }
        }
        for (OttIntent intent : OttIntent.values()) {
            if (!byIntent.containsKey(intent)) {
                throw new IllegalStateException(
                    "No OttIntentHandler bean for intent " + intent);
            }
        }
        this.handlers = byIntent;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Issue an OTT for {@code user} in {@code tenant}. The caller already has
     * the {@link User}, so there is no oracle branch — delivery always happens.
     *
     * <p>{@code @Transactional} so the AFTER_COMMIT audit listener has a
     * transaction to hook onto when this is called outside a wrapping one
     * (e.g. signup mid-flight, before any TenantScope filter has fired).
     */
    @Transactional
    public void issue(OttIntent intent, Tenant tenant, User user) {
        OttIntentHandler handler = handlerFor(intent);
        TenantScope.run(tenant.slug(), tenant.id(), () -> {
            TenantOneTimeToken token = tokenService.generateForIntent(user.email(), intent);
            sendEmail(tenant, user.email(), token.tokenValue(), handler);
        });
        eventPublisher.publishEvent(handler.issuedEvent(tenant, user.id(), user.email(), true));
    }

    /**
     * Issue an OTT for the user identified by {@code email} in {@code tenant}.
     * The user-lookup branch applies the existence-oracle defence: if no user
     * matches the address, the operation is a silent no-op for delivery, but
     * still emits an issued event with {@code delivered=false} so audit shows
     * the attempt without leaking which addresses are real.
     */
    @Transactional
    public void issue(OttIntent intent, Tenant tenant, String email) {
        OttIntentHandler handler = handlerFor(intent);
        Optional<User> maybeUser = userRepository.findByEmailAndTenantId(email, tenant.id());
        boolean delivered = false;
        Long userId = null;
        if (maybeUser.isPresent()) {
            User user = maybeUser.get();
            userId = user.id();
            TenantScope.run(tenant.slug(), tenant.id(), () -> {
                TenantOneTimeToken token = tokenService.generateForIntent(user.email(), intent);
                sendEmail(tenant, user.email(), token.tokenValue(), handler);
            });
            delivered = true;
        }
        eventPublisher.publishEvent(handler.issuedEvent(tenant, userId, email, delivered));
    }

    private OttIntentHandler handlerFor(OttIntent intent) {
        // Constructor guarantees the map covers every intent; the requireNonNull
        // is purely a NullAway witness that Map.get cannot return null here.
        return Objects.requireNonNull(handlers.get(intent),
            () -> "No OttIntentHandler bean for intent " + intent);
    }

    private void sendEmail(Tenant tenant, String recipient, String tokenValue, OttIntentHandler handler) {
        String magicLink = "/t/" + tenant.slug() + "/login/ott?token=" + tokenValue;
        emailSender.send(new EmailMessage(
            recipient, handler.subject(tenant), handler.body(tenant, magicLink)));
    }
}

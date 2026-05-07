package com.stucray.limen.auth.ott;

import com.stucray.limen.audit.events.AuditedDomainEvent;
import com.stucray.limen.tenant.Tenant;
import org.jspecify.annotations.Nullable;

/**
 * Per-intent description for the OTT substrate. One {@code @Component} bean
 * implements this interface for each {@link OttIntent}; {@link OttDispatcher}
 * collects them via Spring map-injection and looks them up by
 * {@link #intent()}.
 *
 * <p>A handler answers three questions about its intent:
 *
 * <ol>
 *   <li>What does the email subject look like for this tenant?</li>
 *   <li>What does the email body look like, given the magic link?</li>
 *   <li>What audit event should be published when the OTT was issued?</li>
 * </ol>
 *
 * <p>Handlers are pure descriptions — they do not own the dispatch lifecycle,
 * the user lookup, the {@code TenantScope} binding, or the transaction
 * boundary. Those are dispatcher concerns and stay there. Adding a new OTT
 * intent therefore requires edits in exactly two places: a new
 * {@code OttIntentHandler} bean and a new audit event record. Zero edits to
 * the dispatcher, completion service, contract handler, or any existing
 * handler.
 */
public interface OttIntentHandler {

    OttIntent intent();

    String subject(Tenant tenant);

    String body(Tenant tenant, String magicLink);

    /**
     * Build the audit event for an issue attempt. {@code userId} is null and
     * {@code delivered} is false on the existence-oracle silent-skip path
     * (the dispatcher applies that defence uniformly across intents).
     */
    AuditedDomainEvent issuedEvent(
        Tenant tenant, @Nullable Long userId, String email, boolean delivered);
}

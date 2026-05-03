package com.stucray.limen.audit.events;

/**
 * A user finished the OTT-driven password-reset flow: clicked the magic link,
 * authenticated via OTT, and submitted a new password. The accompanying
 * {@link PasswordChangedEvent} (trigger {@code SELF_SERVICE}) covers the hash
 * rotation; this event marks completion of the reset journey specifically so
 * audit can correlate the issue → consume → completion arc.
 */
public record PasswordResetCompletedEvent(
    Long tenantId,
    Long userId
) {}

package com.stucray.limen.audit.events;

/**
 * A verification OTT was minted for a user — typically at signup, or as the
 * "resend verification email" path. The {@code email} is recorded directly
 * because at issue time the row may have just been inserted and any consumer
 * looking up "what address received this token?" should not have to join.
 */
public record VerificationOttIssuedEvent(
    Long tenantId,
    Long userId,
    String email
) {}

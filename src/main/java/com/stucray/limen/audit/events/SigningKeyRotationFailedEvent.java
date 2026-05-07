package com.stucray.limen.audit.events;

/**
 * Metrics-only signal that a per-tenant rotation threw mid-batch. Deliberately
 * does not implement {@link AuditedDomainEvent} — there is no audit row for a
 * rotation failure; the listener increments
 * {@code limen.security.signing_key.rotation.failure{cause=...}} and the
 * exception itself is logged WARN at the catch site. {@code cause} is the
 * thrown exception's simple class name, mirroring the
 * {@code limen.auth.login.failure{cause=...}} recipe.
 */
public record SigningKeyRotationFailedEvent(long tenantId, String cause) {}

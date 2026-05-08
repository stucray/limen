package com.stucray.limen.audit.dispatch;

/**
 * Which Spring listener method handles a given audit-bearing event-type. Encoded
 * as data on each {@link AuditRule} so the choice is visible alongside the event
 * class instead of buried in a per-method annotation.
 */
enum AuditBinding {
    /**
     * Custom domain event emitted from inside a {@code @Transactional} method.
     * Audit row is written after the parent transaction commits, so a write
     * failure cannot roll back the user-facing action.
     */
    AFTER_COMMIT,

    /**
     * Pre-transaction event (rate-limit hits, Spring Security auth events).
     * Audit row is written immediately at publish time. The dispatcher swallows
     * write failures so audit problems can never break the publishing path.
     */
    IMMEDIATE
}

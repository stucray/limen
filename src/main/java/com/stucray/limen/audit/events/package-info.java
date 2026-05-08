/**
 * Cross-module API for the audit log: the domain event types that other
 * modules publish via {@code ApplicationEventPublisher}.
 *
 * <p>Every concrete event in this package is an immutable {@code record}
 * that implements the {@link AuditedDomainEvent} marker. Implementing the
 * marker is what binds an event to the AFTER_COMMIT audit listener
 * ({@code AuditDispatcher.onAfterCommit}); see {@link AuditedDomainEvent}'s
 * Javadoc for why the marker is load-bearing rather than decorative.
 *
 * <p>To add a new audited event: declare a {@code record} in this package
 * implementing {@link AuditedDomainEvent}, add its mapping to
 * {@code AuditRegistry}, and publish it from your module's transactional
 * code. No other coordination is needed — the listener side is wired here.
 *
 * <p>Spring Modulith {@code @NamedInterface}; sub-packages of {@code audit}
 * other than this one ({@code audit.dispatch}) are internal to the
 * {@code audit} module.
 */
@NamedInterface("events")
package com.stucray.limen.audit.events;

import org.springframework.modulith.NamedInterface;
